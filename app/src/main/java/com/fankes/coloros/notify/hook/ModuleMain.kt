package com.fankes.coloros.notify.hook

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.ImageView
import androidx.core.os.BundleCompat
import com.fankes.coloros.notify.bean.IconDataBean
import com.fankes.coloros.notify.const.ModuleInfo
import com.fankes.coloros.notify.const.PackageName
import com.fankes.coloros.notify.data.ConfigData
import com.fankes.coloros.notify.utils.tool.BitmapCompatTool
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileInputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class ModuleMain : XposedModule() {

    companion object {
        private const val EXTRA_ORIGINAL_SMALL_ICON = "com.fankes.coloros.notify.original_small_icon"

        /**
         * 状态栏规则图标归一化输出最小尺寸（px）。
         *
         * 说明：部分在线规则图标源尺寸为 40/50/72 px，ColorOS 16 的状态栏图标渲染路径可能不会对
         * 小尺寸位图做「放大」，导致在高密度屏幕上视觉特别小，因此这里保证输出尺寸不低于一定阈值。
         */
        private const val STATUS_BAR_ICON_MIN_OUTPUT_SIZE_PX = 96

        /** 状态栏图标内容尽可能贴近画布（更激进） */
        private const val STATUS_BAR_ICON_CONTENT_SCALE = 1.0f

        /** 透明裁边阈值（0~255），值越大裁得越狠 */
        private const val STATUS_BAR_ICON_ALPHA_THRESHOLD = 16
    }

    private val onceLogs = ConcurrentHashMap.newKeySet<String>()
    private var systemServerInstalled = false
    private var systemUiInstalled = false
    private var systemServerSnapshot = ConfigData.defaultHookSnapshot()
    private var systemUiSnapshot = ConfigData.defaultHookSnapshot()
    private var systemUiRules: Map<String, IconDataBean> = emptyMap()
    @Volatile
    private var systemServerConfigVersion = -1L
    @Volatile
    private var systemServerLastRefreshAt = 0L

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        emitLog(
            Log.INFO,
            "模块已加载：process=${param.processName}, framework=$frameworkName, api=$apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (systemServerInstalled) return
        systemServerSnapshot = ConfigData.readHookSnapshot(remotePrefsOrNull())
        systemServerConfigVersion = remotePrefsOrNull()?.getLong(ConfigData.KEY_CONFIG_UPDATED_AT, 0L) ?: 0L
        systemServerLastRefreshAt = SystemClock.elapsedRealtime()
        systemServerInstalled = installSystemServerHook(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PackageName.SYSTEM_UI || !param.isFirstPackage || systemUiInstalled) return
        reloadSystemUiConfig()
        systemUiInstalled = installSystemUiHooks(param.classLoader)
    }

    private fun reloadSystemUiConfig() {
        systemUiSnapshot = ConfigData.readHookSnapshot(remotePrefsOrNull())
        val rulesJson = loadRemoteRulesJson()
        systemUiRules = ConfigData.parseRules(rulesJson).associateBy { it.packageName }
        emitLog(
            Log.INFO,
            "SystemUI 配置已加载：module=${systemUiSnapshot.moduleEnabled}, enhancement=${systemUiSnapshot.iconEnhancementEnabled}, rules=${systemUiRules.size}"
        )
        val mirroredRuleCount = remotePrefsOrNull()?.getInt(ConfigData.KEY_RULES_COUNT, 0) ?: 0
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
        val fixSmallIconMethod = ReflectHelper.findMethod(
            helperClass,
            "fixSmallIcon",
            Notification::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return warnAndFalse("system.fix.method", "未找到 fixSmallIcon(Notification, String, String, boolean)")

        hook(fixSmallIconMethod).intercept { chain ->
            refreshSystemServerConfigIfNeeded()
            if (!systemServerSnapshot.moduleEnabled || !systemServerSnapshot.iconEnhancementEnabled) {
                return@intercept chain.proceed()
            }
            val notification = chain.args.firstOrNull() as? Notification
            val originalIcon = notification?.smallIcon
            if (notification != null && originalIcon != null) {
                runCatching {
                    notification.extras.putParcelable(EXTRA_ORIGINAL_SMALL_ICON, originalIcon)
                }.onFailure {
                    warnOnce("system.fix.cache.original", "缓存原始 smallIcon 失败", it)
                }
            }
            infoOnce("system.fix.hit", "fixSmallIcon 已命中，已缓存原始 smallIcon，通知中心继续保留 ColorOS 原始逻辑")
            chain.proceed()
        }
        emitLog(
            Log.INFO,
            "system_server Hook 已安装：fixSmallIcon 原始 smallIcon 缓存, module=${systemServerSnapshot.moduleEnabled}, enhancement=${systemServerSnapshot.iconEnhancementEnabled}"
        )
        return true
    }

    private fun refreshSystemServerConfigIfNeeded(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - systemServerLastRefreshAt < 1_000L) return
        systemServerLastRefreshAt = now
        val remotePrefs = remotePrefsOrNull() ?: return
        val remoteVersion = remotePrefs.getLong(ConfigData.KEY_CONFIG_UPDATED_AT, 0L)
        if (!force && remoteVersion == systemServerConfigVersion) return
        systemServerSnapshot = ConfigData.readHookSnapshot(remotePrefs)
        systemServerConfigVersion = remoteVersion
        infoOnce(
            "system.fix.reload",
            "system_server 配置已刷新：module=${systemServerSnapshot.moduleEnabled}, enhancement=${systemServerSnapshot.iconEnhancementEnabled}"
        )
    }

    private fun installSystemUiHooks(classLoader: ClassLoader): Boolean {
        val members = SystemUiMembers.resolve(classLoader, ::warnOnce) ?: return false

        loadClassOrNull(
            "com.oplus.systemui.statusbar.notification.util.OplusNotificationSmallIconUtil",
            classLoader
        )?.let { utilClass ->
            ReflectHelper.findMethod(
                utilClass,
                "useAppIconForSmallIcon",
                Notification::class.java,
            )?.let { method ->
                hook(method).intercept { false }
                emitLog(Log.INFO, "SystemUI Hook：已禁用 OplusNotificationSmallIconUtil.useAppIconForSmallIcon")
            } ?: warnOnce(
                "systemui.useAppIcon.missing",
                "未找到 OplusNotificationSmallIconUtil.useAppIconForSmallIcon(Notification)"
            )
        }

        hook(members.statusBarUpdateGrayScale).intercept { chain ->
            val drawable = chain.args.getOrNull(0) as? Drawable ?: return@intercept chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) ?: return@intercept chain.proceed()
            val sbn = chain.args.getOrNull(2) as? StatusBarNotification ?: return@intercept chain.proceed()
            runCatching {
                members.statusBarSetIsIconColorable.invoke(statusBarIconView, BitmapCompatTool.isGrayscaleDrawable(drawable))
            }.onFailure {
                warnOnce("systemui.statusbar.grayscale", "状态栏灰度判定替换失败", it)
                return@intercept chain.proceed()
            }
            null
        }

        hook(members.iconManagerGetIconDescriptor).intercept { chain ->
            if (!systemUiSnapshot.moduleEnabled || !systemUiSnapshot.iconEnhancementEnabled) {
                return@intercept chain.proceed()
            }
            val result = chain.proceed()
            val statusBarIcon = result ?: return@intercept result
            val notificationEntry = chain.args.getOrNull(0) ?: return@intercept statusBarIcon
            val iconManager = chain.thisObject ?: return@intercept statusBarIcon
            val context = runCatching {
                val iconBuilder = members.iconManagerIconBuilderField.get(iconManager)
                members.iconBuilderContextField.get(iconBuilder) as? Context
            }.getOrNull() ?: return@intercept statusBarIcon
            val sbn = runCatching {
                members.notificationEntryGetSbn.invoke(notificationEntry) as? StatusBarNotification
            }.getOrNull() ?: return@intercept statusBarIcon
            val currentStatusBarIcon = runCatching {
                members.statusBarIconField.get(statusBarIcon) as? Icon
            }.getOrNull()
            val replacementIcon = resolveStatusBarReplacementIcon(context, sbn, currentStatusBarIcon)
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
        return true
    }

    private fun resolveStatusBarReplacementIcon(
        context: Context,
        sbn: StatusBarNotification,
        currentStatusBarIcon: Icon?,
    ): Icon? {
        if (!systemUiSnapshot.moduleEnabled || !systemUiSnapshot.iconEnhancementEnabled) return null
        val packageName = sbn.packageName.orEmpty()
        val preservedOriginalIcon = runCatching {
            BundleCompat.getParcelable(sbn.notification.extras, EXTRA_ORIGINAL_SMALL_ICON, Icon::class.java)
        }.getOrNull()
        val baseStatusBarIcon = preservedOriginalIcon ?: sbn.notification.smallIcon
        val statusBarOriginalDrawable = runCatching {
            baseStatusBarIcon?.loadDrawable(context)?.mutate()
        }.getOrNull() ?: return null
        val originalIsGrayscale = BitmapCompatTool.isGrayscaleDrawable(statusBarOriginalDrawable)
        val currentIsGrayscale = runCatching {
            currentStatusBarIcon?.loadDrawable(context)?.mutate()?.let(BitmapCompatTool::isGrayscaleDrawable)
        }.getOrNull()
        if (originalIsGrayscale && currentIsGrayscale == false) {
            infoOnce(
                "systemui.statusbar.prefer.original:$packageName",
                "检测到应用已提供原生灰度 smallIcon，但 SystemUI 当前使用彩色图标，已恢复为原始 smallIcon"
            )
            return baseStatusBarIcon
        }

        val rule = systemUiRules[packageName]?.takeIf { it.isEnabled } ?: return null
        val shouldUseRule = rule.isEnabledAll || !originalIsGrayscale
        if (!shouldUseRule) return null
        infoOnce("systemui.statusbar.rule.hit", "SystemUI 命中状态栏规则图标路径")
        val outputSize = resolveStatusBarIconOutputSizePx(context, rule.iconBitmap)
        val normalizedBitmap = runCatching {
            BitmapCompatTool.normalizeIconBitmap(
                bitmap = rule.iconBitmap,
                outputSize = outputSize,
                contentScale = STATUS_BAR_ICON_CONTENT_SCALE,
                alphaThreshold = STATUS_BAR_ICON_ALPHA_THRESHOLD,
            )
        }.getOrElse { rule.iconBitmap }
        return runCatching { Icon.createWithBitmap(normalizedBitmap) }.getOrNull()
    }

    private fun resolveStatusBarIconOutputSizePx(context: Context, source: Bitmap): Int {
        val density = context.resources?.displayMetrics?.density ?: 0f
        val dp24Px = (24f * density).roundToInt().coerceAtLeast(1)
        return maxOf(
            STATUS_BAR_ICON_MIN_OUTPUT_SIZE_PX,
            dp24Px,
            source.width,
            source.height,
        ).coerceIn(24, 192)
    }

    private fun remotePrefsOrNull(): SharedPreferences? = runCatching {
        getRemotePreferences(ConfigData.GROUP_CONFIG)
    }.getOrNull()

    private fun loadRemoteRulesJson(): String = runCatching {
        openRemoteFile(ConfigData.RULES_FILE_NAME).use { pfd ->
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
            Log.println(priority, ModuleInfo.LOG_TAG, message)
        } else {
            log(priority, ModuleInfo.LOG_TAG, message, throwable)
            Log.println(priority, ModuleInfo.LOG_TAG, "$message\n${Log.getStackTraceString(throwable)}")
        }
    }

    private fun warnAndFalse(key: String, message: String): Boolean {
        warnOnce(key, message)
        return false
    }

    private fun loadClassOrNull(name: String, classLoader: ClassLoader): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrElse {
        warnOnce("class:$name", "未找到类：$name", it)
        null
    }

    private data class SystemUiMembers(
        val statusBarUpdateGrayScale: Method,
        val statusBarSetIsIconColorable: Method,
        val iconManagerGetIconDescriptor: Method,
        val iconManagerIconBuilderField: Field,
        val iconBuilderContextField: Field,
        val notificationEntryGetSbn: Method,
        val statusBarIconField: Field,
        val statusBarPreloadedIconField: Field,
    ) {
        companion object {
            fun resolve(
                classLoader: ClassLoader,
                warn: (String, String, Throwable?) -> Unit,
            ): SystemUiMembers? {
                fun warnMissing(key: String, message: String): SystemUiMembers? {
                    warn(key, message, null)
                    return null
                }

                fun load(name: String): Class<*>? = runCatching {
                    Class.forName(name, false, classLoader)
                }.getOrElse {
                    warn("class:$name", "未找到类：$name", it)
                    null
                }

                val statusBarIconViewClass = load("com.android.systemui.statusbar.StatusBarIconView")
                    ?: return null
                val statusBarIconControllerClass = load("com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl") ?: return null
                val iconManagerClass = load("com.android.systemui.statusbar.notification.icon.IconManager") ?: return null
                val iconBuilderClass = load("com.android.systemui.statusbar.notification.icon.IconBuilder") ?: return null
                val notificationEntryClass = load("com.android.systemui.statusbar.notification.collection.NotificationEntry") ?: return null
                val statusBarIconClass = load("com.android.internal.statusbar.StatusBarIcon") ?: return null

                val statusBarUpdateGrayScale = ReflectHelper.findMethod(
                    statusBarIconControllerClass,
                    "updateStatusBarIconGrayScale",
                    Drawable::class.java,
                    statusBarIconViewClass,
                    StatusBarNotification::class.java,
                ) ?: return warnMissing(
                    "member:statusbar.gray",
                    "未找到 StatusBarIconControllerExImpl.updateStatusBarIconGrayScale"
                )
                val statusBarSetIsIconColorable = ReflectHelper.findMethod(
                    statusBarIconViewClass,
                    "setIsIconColorable",
                    Boolean::class.javaPrimitiveType!!,
                ) ?: return warnMissing("member:statusbar.colorable", "未找到 StatusBarIconView.setIsIconColorable(boolean)")
                val iconManagerGetIconDescriptor = ReflectHelper.findMethod(
                    iconManagerClass,
                    "getIconDescriptor",
                    notificationEntryClass,
                    Boolean::class.javaPrimitiveType!!,
                ) ?: return warnMissing("member:iconmanager.getIconDescriptor", "未找到 IconManager.getIconDescriptor(NotificationEntry, boolean)")
                val iconManagerIconBuilderField = ReflectHelper.findField(iconManagerClass, "iconBuilder")
                    ?: return warnMissing("member:iconmanager.iconBuilder", "未找到 IconManager.iconBuilder")
                val iconBuilderContextField = ReflectHelper.findField(iconBuilderClass, "context")
                    ?: return warnMissing("member:iconbuilder.context", "未找到 IconBuilder.context")
                val notificationEntryGetSbn = ReflectHelper.findMethod(notificationEntryClass, "getSbn")
                    ?: return warnMissing("member:entry.getSbn", "未找到 NotificationEntry.getSbn()")
                val statusBarIconField = ReflectHelper.findField(statusBarIconClass, "icon")
                    ?: return warnMissing("member:statusbar.icon", "未找到 StatusBarIcon.icon")
                val statusBarPreloadedIconField = ReflectHelper.findField(statusBarIconClass, "preloadedIcon")
                    ?: return warnMissing("member:statusbar.preloaded", "未找到 StatusBarIcon.preloadedIcon")

                return SystemUiMembers(
                    statusBarUpdateGrayScale = statusBarUpdateGrayScale,
                    statusBarSetIsIconColorable = statusBarSetIsIconColorable,
                    iconManagerGetIconDescriptor = iconManagerGetIconDescriptor,
                    iconManagerIconBuilderField = iconManagerIconBuilderField,
                    iconBuilderContextField = iconBuilderContextField,
                    notificationEntryGetSbn = notificationEntryGetSbn,
                    statusBarIconField = statusBarIconField,
                    statusBarPreloadedIconField = statusBarPreloadedIconField,
                )
            }
        }
    }

    private object ReflectHelper {

        fun findField(clazz: Class<*>, name: String): Field? {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredFields.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true
                    return it
                }
                current = current.superclass
            }
            return null
        }

        fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredMethods.firstOrNull { method ->
                    method.name == name && method.parameterTypes.contentEquals(params)
                }?.let {
                    it.isAccessible = true
                    return it
                }
                current.declaredMethods.firstOrNull { method ->
                    method.name == name && method.parameterTypes.size == params.size
                }?.let {
                    it.isAccessible = true
                    return it
                }
                current = current.superclass
            }
            return null
        }
    }
}
