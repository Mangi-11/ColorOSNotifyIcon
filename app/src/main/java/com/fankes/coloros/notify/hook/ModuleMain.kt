package com.fankes.coloros.notify.hook

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.fankes.coloros.notify.core.ModuleInfo
import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.hook.icon.IconBitmapClassifier
import com.fankes.coloros.notify.hook.icon.NotificationIconResolver
import com.fankes.coloros.notify.hook.icon.ThemeIconProvider
import com.fankes.coloros.notify.hook.reflect.Reflection
import com.fankes.coloros.notify.hook.systemui.HeaderIconClaimRegistry
import com.fankes.coloros.notify.hook.systemui.SystemUiMembers
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileInputStream
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ModuleMain : XposedModule() {

    companion object {
        private const val EXTRA_ORIGINAL_SMALL_ICON = "com.fankes.coloros.notify.original_small_icon"
        private val OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS = longArrayOf(64L, 180L, 360L)
    }

    private val onceLogs = ConcurrentHashMap.newKeySet<String>()
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()
    private val oplusGroupReapplyTasks = Collections.synchronizedMap(WeakHashMap<ImageView, Runnable>())
    private var systemServerInstalled = false
    private var systemUiInstalled = false
    private var systemUiConfig = RuleStore.ModuleConfig()
    private var systemUiRules: Map<String, IconRule> = emptyMap()
    private var notificationRefreshCoordinator: Any? = null
    private var systemUiRefreshReceiver: BroadcastReceiver? = null
    private var systemUiRefreshReceiverRegistered = false

    private enum class PanelIconTarget {
        Header,
        OplusGroupSummary,
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ThemeIconProvider.setWarningLogger(::warnOnce)
        emitLog(
            Log.INFO,
            "模块已加载：process=${param.processName}, framework=$frameworkName, api=$apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (systemServerInstalled) return
        systemServerInstalled = installSystemServerHook(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SystemPackages.SYSTEM_UI || !param.isFirstPackage || systemUiInstalled) return
        reloadSystemUiConfig()
        systemUiInstalled = installSystemUiHooks(param.classLoader)
    }

    private fun reloadSystemUiConfig() {
        headerIconClaims.clear()
        val remotePrefs = remotePrefsOrNull()
        val rulesJson = loadRemoteRulesJson()
        systemUiConfig = RuleStore.readModuleConfig(remotePrefs)
        ThemeIconProvider.clearCache()
        systemUiRules = RuleStore.applyRuleOverrides(
            rules = RuleStore.parseRules(rulesJson),
            source = remotePrefs,
        ).associateBy { it.packageName }
        emitLog(
            Log.INFO,
            "SystemUI 配置已加载：enabled=${systemUiConfig.moduleEnabled}, rulesEnabled=${systemUiConfig.rulesEnabled}, iconSource=${systemUiConfig.iconSourceMode}, panelEnabled=${systemUiConfig.panelIconReplacementEnabled}, oplusPush=${systemUiConfig.oplusPushSpecialHandlingEnabled}, placeholder=${systemUiConfig.placeholderIconEnabled}, rules=${systemUiRules.size}"
        )
        val mirroredRuleCount = remotePrefs?.getInt(RuleStore.KEY_RULES_COUNT, 0) ?: 0
        if (rulesJson.isBlank() && mirroredRuleCount > 0) {
            warnOnce("systemui.rules.missing", "SystemUI 远程规则文件为空，但远程规则计数不为 0，可能是规则镜像未完成")
        }
        if (systemUiRules.isEmpty()) {
            warnOnce("systemui.rules.empty", "SystemUI 规则为空，通知图标仅保留原始灰度增强")
        }
    }

    private fun installSystemServerHook(classLoader: ClassLoader): Boolean {
        val helperClass = loadClassOrNull("com.android.server.notification.OplusNotificationFixHelper", classLoader)
            ?: return warnAndFalse("system.fix.helper", "未找到 OplusNotificationFixHelper，跳过 system_server Hook")
        val fixSmallIconMethod = Reflection.findMethod(
            helperClass,
            "fixSmallIcon",
            Notification::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return warnAndFalse("system.fix.method", "未找到 fixSmallIcon(Notification, String, String, boolean)")

        moduleHook(fixSmallIconMethod, "system.fixSmallIcon").intercept { chain ->
            val notification = chain.args.firstOrNull() as? Notification
            val originalIcon = notification?.smallIcon
            if (notification != null && originalIcon != null) {
                runCatching {
                    notification.extras.putParcelable(EXTRA_ORIGINAL_SMALL_ICON, originalIcon)
                }.onFailure {
                    warnOnce("system.fix.cache.original", "缓存原始 smallIcon 失败", it)
                }
            }
            infoOnce("system.fix.hit", "fixSmallIcon 已命中，已缓存 ColorOS 修正前的原始 smallIcon")
            chain.proceed()
        }
        emitLog(
            Log.INFO,
            "system_server Hook 已安装：缓存 fixSmallIcon 修正前的原始 smallIcon"
        )
        return true
    }

    private fun installSystemUiHooks(classLoader: ClassLoader): Boolean {
        if (!systemUiConfig.moduleEnabled) {
            warnOnce("systemui.config.disabled", "模块配置已停用，跳过 SystemUI Hook")
            return false
        }
        val members = SystemUiMembers.resolve(classLoader, ::warnOnce) ?: return false
        installSystemUiConfigRefreshHook(members)

        loadClassOrNull(
            "com.oplus.systemui.statusbar.notification.util.OplusNotificationSmallIconUtil",
            classLoader
        )?.let { utilClass ->
            Reflection.findMethod(
                utilClass,
                "useAppIconForSmallIcon",
                Notification::class.java,
            )?.let { method ->
                moduleHook(method, "systemui.useAppIconForSmallIcon").intercept { false }
                emitLog(Log.INFO, "SystemUI Hook：已禁用 OplusNotificationSmallIconUtil.useAppIconForSmallIcon")
            } ?: warnOnce(
                "systemui.useAppIcon.missing",
                "未找到 OplusNotificationSmallIconUtil.useAppIconForSmallIcon(Notification)"
            )
        }

        moduleHook(members.statusBarUpdateGrayScale, "systemui.statusbar.updateGrayScale").intercept { chain ->
            val drawable = chain.args.getOrNull(0) as? Drawable ?: return@intercept chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) ?: return@intercept chain.proceed()
            val sbn = chain.args.getOrNull(2) as? StatusBarNotification ?: return@intercept chain.proceed()
            runCatching {
                members.statusBarSetIsIconColorable.invoke(statusBarIconView, IconBitmapClassifier.isGrayscaleDrawable(drawable))
            }.onFailure {
                warnOnce("systemui.statusbar.grayscale", "状态栏灰度判定替换失败", it)
                return@intercept chain.proceed()
            }
            null
        }

        moduleHook(members.iconManagerGetIconDescriptor, "systemui.statusbar.getIconDescriptor").intercept { chain ->
            val result = chain.proceed()
            val statusBarIcon = result ?: return@intercept result
            val notificationEntry = chain.args.getOrNull(0) ?: return@intercept statusBarIcon
            val iconManager = chain.thisObject ?: return@intercept statusBarIcon
            val context = runCatching {
                val iconBuilder = members.iconManagerIconBuilderField.get(iconManager)
                members.iconBuilderContextField.get(iconBuilder) as? Context
            }.getOrNull() ?: return@intercept statusBarIcon
            ensureSystemUiRefreshReceiver(context, members)
            val sbn = runCatching {
                members.notificationEntryGetSbn.invoke(notificationEntry) as? StatusBarNotification
            }.getOrNull() ?: return@intercept statusBarIcon
            val currentStatusBarIcon = runCatching {
                members.statusBarIconField.get(statusBarIcon) as? Icon
            }.getOrNull()
            val replacementIcon = iconResolver().resolveStatusBarIcon(
                context = context,
                sbn = sbn,
                originalSmallIcon = originalSmallIconOf(sbn),
                currentStatusBarIcon = currentStatusBarIcon,
            )
                ?: return@intercept statusBarIcon
            runCatching {
                members.statusBarIconField.set(statusBarIcon, replacementIcon)
                members.statusBarPreloadedIconField.set(statusBarIcon, null)
            }.onFailure {
                warnOnce("systemui.statusbar.icon.replace", "状态栏规则图标注入失败", it)
            }
            statusBarIcon
        }
        emitLog(
            Log.INFO,
            "SystemUI Hook 已安装：状态栏图标路径（IconManager.getIconDescriptor）"
        )
        installNotificationPanelHooks(members)
        return true
    }

    private fun installSystemUiConfigRefreshHook(members: SystemUiMembers) {
        members.viewConfigCoordinatorConstructors.forEach { constructor ->
            runCatching {
                moduleHook(
                    constructor,
                    "systemui.refresh.coordinator.constructor.${constructor.parameterTypes.size}"
                ).intercept { chain ->
                    val result = chain.proceed()
                    notificationRefreshCoordinator = chain.thisObject ?: notificationRefreshCoordinator
                    result
                }
            }.onFailure {
                warnOnce("systemui.refresh.constructor", "安装 ViewConfigCoordinator 构造函数 Hook 失败", it)
            }
        }
        members.viewConfigCoordinatorAttach?.let { method ->
            runCatching {
                moduleHook(method, "systemui.refresh.coordinator.attach").intercept { chain ->
                    val result = chain.proceed()
                    notificationRefreshCoordinator = chain.thisObject ?: notificationRefreshCoordinator
                    result
                }
            }.onFailure {
                warnOnce("systemui.refresh.attach", "安装 ViewConfigCoordinator.attach Hook 失败", it)
            }
        }
        if (members.viewConfigCoordinatorRefreshNotifications == null) {
            warnOnce(
                "systemui.refresh.member.missing",
                "未找到 ViewConfigCoordinator.updateNotificationsOnDensityOrFontScaleChanged，配置变更将等待通知自身刷新"
            )
            return
        }
        infoOnce("systemui.refresh.hook", "SystemUI Hook 已安装：配置变更刷新路径（ViewConfigCoordinator）")
    }

    private fun installNotificationPanelHooks(members: SystemUiMembers) {
        installOplusHeaderColorGuard(members)
        members.notificationHeaderOnContentUpdated?.let { method ->
            moduleHook(method, "systemui.panel.header.onContentUpdated").intercept { chain ->
                val result = chain.proceed()
                val wrapper = chain.thisObject ?: return@intercept result
                applyPanelIconReplacement(members, wrapper, chain.args.firstOrNull())
                result
            }
        }
        members.notificationHeaderResolveHeaderViews?.let { method ->
            moduleHook(method, "systemui.panel.header.resolveHeaderViews").intercept { chain ->
                val result = chain.proceed()
                val wrapper = chain.thisObject ?: return@intercept result
                applyPanelIconReplacement(members, wrapper)
                result
            }
        }
        members.oplusGroupInitIcon?.let { method ->
            moduleHook(method, "systemui.panel.group.initIcon").intercept { chain ->
                val result = chain.proceed()
                val wrapper = chain.thisObject ?: return@intercept result
                applyPanelIconReplacement(members, wrapper, target = PanelIconTarget.OplusGroupSummary)
                result
            }
        }
        members.oplusGroupResolveHeaderViews?.let { method ->
            moduleHook(method, "systemui.panel.group.resolveHeaderViews").intercept { chain ->
                val result = chain.proceed()
                val wrapper = chain.thisObject ?: return@intercept result
                applyPanelIconReplacement(members, wrapper, target = PanelIconTarget.OplusGroupSummary)
                result
            }
        }
        emitLog(Log.INFO, "SystemUI Hook 已安装：通知面板图标路径")
    }

    private fun installOplusHeaderColorGuard(members: SystemUiMembers) {
        val oplusHeader = members.oplusHeader ?: return
        val getIcon = members.notificationHeaderGetIcon ?: return
        val rowField = members.notificationViewWrapperRowField ?: return

        moduleHook(oplusHeader.updateIconColor, "systemui.panel.header.updateIconColor").intercept { chain ->
            if (!systemUiConfig.panelIconReplacementEnabled) return@intercept chain.proceed()
            val extension = chain.thisObject ?: return@intercept chain.proceed()
            val wrapper = runCatching {
                oplusHeader.getBase.invoke(extension)
            }.getOrNull() ?: return@intercept chain.proceed()
            val icon = runCatching {
                getIcon.invoke(wrapper) as? ImageView
            }.getOrNull() ?: return@intercept chain.proceed()
            val row = runCatching {
                rowField.get(wrapper)
            }.getOrNull() ?: return@intercept chain.proceed()
            val sbn = statusBarNotificationFromRow(members, row) ?: return@intercept chain.proceed()
            val drawable = icon.drawable ?: return@intercept chain.proceed()

            if (headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)) false
            else chain.proceed()
        }
        infoOnce(
            "systemui.panel.header.color.guard",
            "SystemUI Hook 已安装：Oplus Header 二次着色保护",
        )
    }

    private fun applyPanelIconReplacement(
        members: SystemUiMembers,
        wrapper: Any,
        rowCandidate: Any? = null,
        iconView: ImageView? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
    ) {
        if (!systemUiConfig.panelIconReplacementEnabled) return
        val row = rowCandidate ?: runCatching {
            members.notificationViewWrapperRowField?.get(wrapper)
        }.getOrNull() ?: return
        val rowView = row as? View
        val icon = iconView ?: when (target) {
            PanelIconTarget.Header -> runCatching {
                members.notificationHeaderGetIcon?.invoke(wrapper) as? ImageView
            }.getOrNull()
            PanelIconTarget.OplusGroupSummary -> rowView?.findOplusGroupSummaryIcon()
        } ?: run {
            if (target == PanelIconTarget.OplusGroupSummary) {
                rowView?.post {
                    applyPanelIconReplacement(members, wrapper, row, iconView, target)
                }
            }
            return
        }
        if (target == PanelIconTarget.Header) headerIconClaims.release(icon)
        ensureSystemUiRefreshReceiver(icon.context, members)
        val sbn = statusBarNotificationFromRow(members, row) ?: return
        val renderPlan = iconResolver().resolvePanelIconPlan(
            context = icon.context,
            sbn = sbn,
            originalSmallIcon = originalSmallIconOf(sbn),
            currentDrawable = icon.drawable,
        ) ?: return
        val effectivePlan = when (target) {
            PanelIconTarget.Header -> renderPlan
            PanelIconTarget.OplusGroupSummary -> renderPlan.asOplusGroupSummaryPlan()
        }
        runCatching {
            if (target == PanelIconTarget.OplusGroupSummary) {
                icon.clearOplusGroupSummaryDecoration()
                icon.applyPanelIconRenderPlan(effectivePlan, target)
                icon.reapplyOplusGroupSummaryIcon(effectivePlan) {
                    statusBarNotificationFromRow(members, row)?.key == sbn.key
                }
                infoOnce("systemui.panel.oplus.group.icon", "SystemUI Hook 已安装：Oplus 聚合摘要图标路径")
            } else {
                icon.applyPanelIconRenderPlan(effectivePlan, target)
                icon.drawable?.let { drawable ->
                    headerIconClaims.claim(icon, sbn.key, drawable)
                }
            }
        }.onFailure {
            warnOnce("systemui.panel.icon.replace", "通知面板规则图标注入失败", it)
        }
    }

    private fun View.findOplusGroupSummaryIcon(): ImageView? {
        val headerId = systemUiId("oplus_notification_collapsed_group_header")
        val containerId = systemUiId("icon_container")
        val iconId = systemUiId("icon")
        if (headerId == 0 || containerId == 0 || iconId == 0) return null
        val header = findViewById<View>(headerId) as? ViewGroup ?: return null
        val container = header.findViewById<View>(containerId) as? ViewGroup ?: header
        return container.findViewById<View>(iconId) as? ImageView
    }

    private fun View.systemUiId(name: String): Int =
        systemUiIdCache[name] ?: resources.getIdentifier(name, "id", SystemPackages.SYSTEM_UI)
            .also { systemUiIdCache[name] = it }

    private fun ImageView.applyPanelIconRenderPlan(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        target: PanelIconTarget,
    ) {
        clearColorFilter()
        imageTintList = null
        background = null
        foreground = null
        clipToOutline = plan.clipToOutline
        if (target == PanelIconTarget.OplusGroupSummary) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        setImageDrawable(plan.drawable)
        plan.tintColor?.let {
            colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun NotificationIconResolver.PanelIconRenderPlan.asOplusGroupSummaryPlan(): NotificationIconResolver.PanelIconRenderPlan =
        copy(
            clipToOutline = false,
        )

    private fun ImageView.clearOplusGroupSummaryDecoration() {
        background = null
        foreground = null
        clipToOutline = false
        imageTintList = null
        clearColorFilter()

        val container = parent as? View
        if (container?.id == systemUiId("icon_container")) {
            container.background = null
            container.foreground = null
            container.clipToOutline = false
            if (container is ViewGroup) {
                container.clipChildren = false
                container.clipToPadding = false
            }
        }
    }

    private fun ImageView.reapplyOplusGroupSummaryIcon(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        isStillSameNotification: () -> Boolean,
    ) {
        oplusGroupReapplyTasks.remove(this)?.let { removeCallbacks(it) }
        val remainingRuns = AtomicInteger(OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS.size)
        lateinit var action: Runnable
        action = Runnable {
            try {
                if (!isAttachedToWindow || !isStillSameNotification()) return@Runnable
                clearOplusGroupSummaryDecoration()
                applyPanelIconRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
            } finally {
                if (remainingRuns.decrementAndGet() <= 0) {
                    synchronized(oplusGroupReapplyTasks) {
                        if (oplusGroupReapplyTasks[this] === action) {
                            oplusGroupReapplyTasks.remove(this)
                        }
                    }
                }
            }
        }
        oplusGroupReapplyTasks[this] = action
        OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS.forEach { delay ->
            postDelayed(action, delay)
        }
    }

    private fun statusBarNotificationFromRow(members: SystemUiMembers, row: Any): StatusBarNotification? {
        val entry = runCatching {
            members.expandableRowGetEntry?.invoke(row)
        }.getOrNull() ?: return null
        return runCatching {
            members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
        }.getOrNull()
    }

    private fun iconResolver() = NotificationIconResolver(
        config = systemUiConfig,
        rules = systemUiRules,
    )

    private fun ensureSystemUiRefreshReceiver(context: Context, members: SystemUiMembers) {
        if (systemUiRefreshReceiverRegistered) return
        synchronized(this) {
            if (systemUiRefreshReceiverRegistered) return
            val appContext = context.applicationContext ?: context
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ModuleInfo.ACTION_REFRESH_SYSTEM_UI_CONFIG) return
                    reloadSystemUiConfig()
                    refreshSystemUiNotifications(members)
                }
            }
            runCatching {
                val filter = IntentFilter(ModuleInfo.ACTION_REFRESH_SYSTEM_UI_CONFIG)
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onSuccess {
                systemUiRefreshReceiver = receiver
                systemUiRefreshReceiverRegistered = true
                infoOnce("systemui.refresh.receiver", "SystemUI Hook 已安装：配置刷新广播接收器")
            }.onFailure {
                warnOnce("systemui.refresh.receiver", "注册 SystemUI 配置刷新广播接收器失败", it)
            }
        }
    }

    private fun refreshSystemUiNotifications(members: SystemUiMembers) {
        val coordinator = notificationRefreshCoordinator ?: return warnOnce(
            "systemui.refresh.coordinator.missing",
            "尚未捕获 ViewConfigCoordinator，配置变更将等待通知自身刷新"
        )
        val refreshMethod = members.viewConfigCoordinatorRefreshNotifications ?: return
        runCatching {
            refreshMethod.invoke(coordinator)
        }.onFailure {
            warnOnce("systemui.refresh.invoke", "刷新 SystemUI 通知视图失败", it)
        }
    }

    private fun originalSmallIconOf(sbn: StatusBarNotification): Icon? {
        val preservedOriginalIcon = runCatching {
            BundleCompat.getParcelable(sbn.notification.extras, EXTRA_ORIGINAL_SMALL_ICON, Icon::class.java)
        }.getOrNull()
        return preservedOriginalIcon ?: sbn.notification.smallIcon
    }

    private fun remotePrefsOrNull(): SharedPreferences? = runCatching {
        getRemotePreferences(RuleStore.GROUP_CONFIG)
    }.getOrNull()

    private fun loadRemoteRulesJson(): String = runCatching {
        openRemoteFile(RuleStore.RULES_FILE_NAME).use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }.getOrDefault("")

    private fun warnOnce(key: String, message: String, throwable: Throwable? = null) {
        if (!onceLogs.add(key)) return
        if (throwable == null) emitLog(Log.WARN, message)
        else emitLog(Log.ERROR, message, throwable)
    }

    private fun infoOnce(key: String, message: String) {
        if (!onceLogs.add(key)) return
        emitLog(Log.INFO, message)
    }

    private fun emitLog(priority: Int, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(priority, ModuleInfo.LOG_TAG, message)
        } else {
            log(priority, ModuleInfo.LOG_TAG, message, throwable)
        }
    }

    private fun moduleHook(origin: Executable, id: String): XposedInterface.HookBuilder =
        hook(origin)
            .setId("${ModuleInfo.LOG_TAG}.$id")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)

    private fun warnAndFalse(key: String, message: String): Boolean {
        warnOnce(key, message)
        return false
    }

    private fun loadClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
        Reflection.loadClassOrNull(name, classLoader) {
            warnOnce("class:$name", "未找到类：$name", it)
        }
}
