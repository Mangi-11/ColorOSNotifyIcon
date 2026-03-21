package com.fankes.coloros.notify.hook

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.TypedValue
import android.widget.ImageView
import com.fankes.coloros.notify.R
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

class ModuleMain : XposedModule() {

    companion object {
        private const val EXTRA_ORIGINAL_SMALL_ICON = "com.fankes.coloros.notify.original_small_icon"
    }

    private val onceLogs = ConcurrentHashMap.newKeySet<String>()
    private var systemServerInstalled = false
    private var systemUiInstalled = false
    private var systemServerSnapshot = ConfigData.defaultHookSnapshot()
    private var systemUiSnapshot = ConfigData.defaultHookSnapshot()
    private var systemUiRules: Map<String, IconDataBean> = emptyMap()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        emitLog(
            Log.INFO,
            "模块已加载：process=${param.processName}, framework=$frameworkName, api=$apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (systemServerInstalled) return
        systemServerSnapshot = ConfigData.readHookSnapshot(remotePrefsOrNull())
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
            "SystemUI 配置已加载：module=${systemUiSnapshot.moduleEnabled}, enhancement=${systemUiSnapshot.iconEnhancementEnabled}, md3=${systemUiSnapshot.md3StyleEnabled}, corner=${systemUiSnapshot.iconCornerDp}, rules=${systemUiRules.size}"
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
            infoOnce("system.fix.hit", "fixSmallIcon 已命中，已缓存原始 smallIcon，通知面板保留 ColorOS 原始逻辑")
            chain.proceed()
        }
        emitLog(
            Log.INFO,
            "system_server Hook 已安装：fixSmallIcon 原始图标缓存, module=${systemServerSnapshot.moduleEnabled}, enhancement=${systemServerSnapshot.iconEnhancementEnabled}"
        )
        return true
    }

    private fun installSystemUiHooks(classLoader: ClassLoader): Boolean {
        val members = SystemUiMembers.resolve(classLoader, ::warnOnce) ?: return false

        hook(members.statusBarUpdateGrayScale).intercept { chain ->
            val drawable = chain.args.getOrNull(0) as? Drawable ?: return@intercept chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) ?: return@intercept chain.proceed()
            val sbn = chain.args.getOrNull(2) as? StatusBarNotification
            val target = sbn?.let {
                resolveTargetIcon((statusBarIconView as? ImageView)?.context ?: return@intercept chain.proceed(), it)
            } ?: return@intercept chain.proceed()
            if (target.shouldOverrideStatusBar) {
                runCatching {
                    members.statusBarSetIsIconColorable.invoke(
                        statusBarIconView,
                        target.usesRule || BitmapCompatTool.isGrayscaleDrawable(target.statusBarDrawable)
                    )
                }.onFailure {
                    warnOnce("systemui.statusbar.grayscale.rule", "状态栏规则图灰度标记失败", it)
                }
                return@intercept null
            }
            runCatching {
                members.statusBarSetIsIconColorable.invoke(statusBarIconView, BitmapCompatTool.isGrayscaleDrawable(drawable))
            }.onFailure {
                warnOnce("systemui.statusbar.grayscale", "状态栏灰度判定替换失败", it)
                return@intercept chain.proceed()
            }
            null
        }

        hook(members.statusBarControllerUpdateDrawable).intercept { chain ->
            val drawable = chain.args.getOrNull(0) as? Drawable ?: return@intercept chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) as? ImageView ?: return@intercept chain.proceed()
            val sbn = chain.args.getOrNull(2) as? StatusBarNotification ?: return@intercept chain.proceed()
            val target = resolveTargetIcon(statusBarIconView.context, sbn) ?: return@intercept chain.proceed()
            if (!target.shouldOverrideStatusBar) return@intercept chain.proceed()
            infoOnce(
                "systemui.statusbar.controller.hit",
                if (target.usesRule) "SystemUI 命中 Oplus 状态栏最终出图路径（规则图）"
                else "SystemUI 命中 Oplus 状态栏最终出图路径（原始 smallIcon）"
            )
            statusBarIconView.background = null
            statusBarIconView.clearColorFilter()
            statusBarIconView.setPadding(0, 0, 0, 0)
            statusBarIconView.setImageDrawable(target.statusBarDrawable.mutate())
            runCatching {
                members.statusBarSetIsIconColorable.invoke(
                    statusBarIconView,
                    target.usesRule || BitmapCompatTool.isGrayscaleDrawable(target.statusBarDrawable)
                )
            }.onFailure {
                warnOnce("systemui.statusbar.controller.colorable", "状态栏规则图颜色标记失败", it)
            }
            true
        }

        hook(members.statusBarGetIcon).intercept { chain ->
            val statusBarIconView = chain.thisObject
            val sbn = runCatching {
                members.statusBarNotificationField.get(statusBarIconView) as? StatusBarNotification
            }.getOrNull()
            val iconView = statusBarIconView as? ImageView ?: return@intercept chain.proceed()
            val target = sbn?.let { resolveTargetIcon(iconView.context, it) }
                ?.takeIf { it.shouldOverrideStatusBar && it.statusBarReplacementIcon != null }
                ?: return@intercept chain.proceed()
            val replacementIcon = target.statusBarReplacementIcon ?: return@intercept chain.proceed()
            infoOnce(
                "systemui.statusbar.hit",
                if (target.usesRule) "SystemUI 命中状态栏规则图标路径"
                else "SystemUI 命中状态栏原始 smallIcon 路径"
            )
            val statusBarIcon = chain.args.firstOrNull() ?: return@intercept chain.proceed()
            val originalIcon = runCatching { members.statusBarIconField.get(statusBarIcon) }.getOrNull()
            val originalPreloaded = runCatching { members.statusBarPreloadedIconField.get(statusBarIcon) }.getOrNull()
            runCatching {
                members.statusBarIconField.set(statusBarIcon, replacementIcon)
                members.statusBarPreloadedIconField.set(statusBarIcon, null)
            }.getOrElse {
                warnOnce("systemui.statusbar.icon.replace", "状态栏规则图标注入失败", it)
                return@intercept chain.proceed()
            }
            try {
                chain.proceed().also {
                    runCatching { members.statusBarSetIsIconColorable.invoke(statusBarIconView, true) }
                }
            } finally {
                runCatching { members.statusBarIconField.set(statusBarIcon, originalIcon) }
                runCatching { members.statusBarPreloadedIconField.set(statusBarIcon, originalPreloaded) }
            }
        }
        emitLog(
            Log.INFO,
            "SystemUI Hook 已安装：仅状态栏图标路径（StatusBarIconControllerExImpl + StatusBarIconView.getIcon）"
        )
        return true
    }

    private fun applyNotificationIcon(iconView: ImageView?, sbn: StatusBarNotification?) {
        if (iconView == null || sbn == null) return
        val snapshot = systemUiSnapshot
        if (!snapshot.moduleEnabled || !snapshot.iconEnhancementEnabled) {
            clearCustomStyle(iconView)
            return
        }
        val target = resolveTargetIcon(iconView.context, sbn) ?: run {
            clearCustomStyle(iconView)
            return
        }
        if (snapshot.md3StyleEnabled) {
            applyMd3Style(
                iconView = iconView,
                drawable = target.drawable,
                chipColor = resolveChipColor(iconView.context, target.explicitColor, target.notificationColor),
                cornerDp = snapshot.iconCornerDp,
            )
        } else {
            applyClassicTint(
                iconView = iconView,
                drawable = target.drawable,
                tintColor = resolveMonoTint(iconView.context, target.explicitColor, target.notificationColor),
            )
        }
    }

    private fun resolveTargetIcon(context: Context, sbn: StatusBarNotification): ResolvedIcon? {
        val packageName = sbn.packageName.orEmpty()
        val preservedOriginalIcon = runCatching {
            sbn.notification.extras.getParcelable(EXTRA_ORIGINAL_SMALL_ICON) as? Icon
        }.getOrNull()
        val baseStatusBarIcon = preservedOriginalIcon ?: sbn.notification.smallIcon
        val statusBarOriginalDrawable = runCatching {
            baseStatusBarIcon?.loadDrawable(context)?.mutate()
        }.getOrNull() ?: return null
        val rule = systemUiRules[packageName]?.takeIf { it.isEnabled }
        val originalIsGrayscale = BitmapCompatTool.isGrayscaleDrawable(statusBarOriginalDrawable)
        val shouldUseRule = rule != null && (rule.isEnabledAll || !originalIsGrayscale)
        val statusBarBitmap = if (shouldUseRule) {
            BitmapCompatTool.normalizeIconBitmap(rule!!.iconBitmap)
        } else {
            null
        }
        val shouldUsePreservedOriginal = !shouldUseRule && preservedOriginalIcon != null
        val targetDrawable = when {
            shouldUseRule -> BitmapDrawable(context.resources, rule!!.iconBitmap).mutate()
            shouldUsePreservedOriginal -> statusBarOriginalDrawable
            originalIsGrayscale -> statusBarOriginalDrawable
            else -> null
        } ?: return null
        val statusBarDrawable = when {
            shouldUseRule && statusBarBitmap != null -> BitmapDrawable(context.resources, statusBarBitmap).mutate()
            else -> targetDrawable
        }
        return ResolvedIcon(
            drawable = targetDrawable,
            statusBarDrawable = statusBarDrawable,
            explicitColor = if (shouldUseRule) rule?.iconColor ?: 0 else 0,
            notificationColor = sbn.notification.color,
            usesRule = shouldUseRule,
            statusBarBitmap = statusBarBitmap,
            statusBarReplacementIcon = when {
                shouldUseRule && statusBarBitmap != null -> Icon.createWithBitmap(statusBarBitmap)
                shouldUsePreservedOriginal -> preservedOriginalIcon
                else -> null
            },
            shouldOverrideStatusBar = shouldUseRule || shouldUsePreservedOriginal,
        )
    }

    private fun applyMd3Style(iconView: ImageView, drawable: Drawable, chipColor: Int, cornerDp: Int) {
        val density = iconView.resources.displayMetrics.density
        val cornerRadius = cornerDp.coerceIn(0, 24) * density
        val padding = (2 * density).toInt().coerceAtLeast(0)
        iconView.setImageDrawable(drawable)
        iconView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(chipColor)
        }
        iconView.setColorFilter(resolveGlyphTint(iconView.context), PorterDuff.Mode.SRC_IN)
        iconView.setPadding(padding, padding, padding, padding)
        iconView.clipToOutline = false
        iconView.setTag(R.id.tag_notify_icon_customized, true)
    }

    private fun applyClassicTint(iconView: ImageView, drawable: Drawable, tintColor: Int) {
        iconView.setImageDrawable(drawable)
        iconView.background = null
        iconView.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        iconView.setPadding(0, 0, 0, 0)
        iconView.clipToOutline = true
        iconView.setTag(R.id.tag_notify_icon_customized, true)
    }

    private fun clearCustomStyle(iconView: ImageView) {
        if (iconView.getTag(R.id.tag_notify_icon_customized) != true) return
        iconView.background = null
        iconView.clearColorFilter()
        iconView.setPadding(0, 0, 0, 0)
        iconView.clipToOutline = true
        iconView.setTag(R.id.tag_notify_icon_customized, false)
    }

    private fun resolveChipColor(context: Context, explicitColor: Int, notificationColor: Int): Int {
        if (isUsableColor(explicitColor)) return explicitColor
        if (isUsableColor(notificationColor)) return notificationColor
        val fallback = if (isDarkMode(context)) 0xFF707173.toInt() else 0xFF5A6EEA.toInt()
        return resolveThemeColor(context, android.R.attr.colorAccent, fallback)
    }

    private fun resolveMonoTint(context: Context, explicitColor: Int, notificationColor: Int): Int {
        if (isUsableColor(explicitColor)) return explicitColor
        if (isUsableColor(notificationColor)) return notificationColor
        return if (isDarkMode(context)) 0xFFDCDCDC.toInt() else 0xFF707173.toInt()
    }

    private fun resolveGlyphTint(context: Context): Int =
        if (isDarkMode(context)) 0xFFDCDCDC.toInt() else Color.WHITE

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!context.theme.resolveAttribute(attr, typedValue, true)) return fallback
        return when {
            typedValue.resourceId != 0 -> runCatching { context.getColor(typedValue.resourceId) }.getOrDefault(fallback)
            typedValue.data != 0 -> typedValue.data
            else -> fallback
        }
    }

    private fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isUsableColor(color: Int): Boolean = color != 0 && Color.alpha(color) != 0

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
        val statusBarControllerUpdateDrawable: Method,
        val statusBarGetIcon: Method,
        val statusBarNotificationField: Field,
        val statusBarSetIsIconColorable: Method,
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
                val statusBarIconControllerClass = load("com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl")
                    ?: return null
                val statusBarIconClass = load("com.android.internal.statusbar.StatusBarIcon")
                    ?: return null

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
                val statusBarControllerUpdateDrawable = ReflectHelper.findMethod(
                    statusBarIconControllerClass,
                    "updateStatusBarIconDrawable",
                    Drawable::class.java,
                    statusBarIconViewClass,
                    StatusBarNotification::class.java,
                ) ?: return warnMissing(
                    "member:statusbar.controller.drawable",
                    "未找到 StatusBarIconControllerExImpl.updateStatusBarIconDrawable"
                )
                val statusBarGetIcon = ReflectHelper.findMethod(
                    statusBarIconViewClass,
                    "getIcon",
                    statusBarIconClass,
                ) ?: return warnMissing("member:statusbar.getIcon", "未找到 StatusBarIconView.getIcon(StatusBarIcon)")
                val statusBarNotificationField = ReflectHelper.findField(statusBarIconViewClass, "mNotification")
                    ?: return warnMissing("member:statusbar.notification", "未找到 StatusBarIconView.mNotification")
                val statusBarSetIsIconColorable = ReflectHelper.findMethod(
                    statusBarIconViewClass,
                    "setIsIconColorable",
                    Boolean::class.javaPrimitiveType!!,
                ) ?: return warnMissing("member:statusbar.colorable", "未找到 StatusBarIconView.setIsIconColorable(boolean)")
                val statusBarIconField = ReflectHelper.findField(statusBarIconClass, "icon")
                    ?: return warnMissing("member:statusbar.icon", "未找到 StatusBarIcon.icon")
                val statusBarPreloadedIconField = ReflectHelper.findField(statusBarIconClass, "preloadedIcon")
                    ?: return warnMissing("member:statusbar.preloaded", "未找到 StatusBarIcon.preloadedIcon")

                return SystemUiMembers(
                    statusBarUpdateGrayScale = statusBarUpdateGrayScale,
                    statusBarControllerUpdateDrawable = statusBarControllerUpdateDrawable,
                    statusBarGetIcon = statusBarGetIcon,
                    statusBarNotificationField = statusBarNotificationField,
                    statusBarSetIsIconColorable = statusBarSetIsIconColorable,
                    statusBarIconField = statusBarIconField,
                    statusBarPreloadedIconField = statusBarPreloadedIconField,
                )
            }
        }
    }

    private data class ResolvedIcon(
        val drawable: Drawable,
        val statusBarDrawable: Drawable,
        val explicitColor: Int,
        val notificationColor: Int,
        val usesRule: Boolean,
        val statusBarBitmap: Bitmap?,
        val statusBarReplacementIcon: Icon?,
        val shouldOverrideStatusBar: Boolean,
    )

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
