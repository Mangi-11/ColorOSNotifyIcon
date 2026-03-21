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
            infoOnce("system.fix.hit", "fixSmallIcon 已命中，已缓存原始 smallIcon，通知面板保留 ColorOS 原始逻辑")
            chain.proceed()
        }
        emitLog(
            Log.INFO,
            "system_server Hook 已安装：fixSmallIcon 原始图标缓存, module=${systemServerSnapshot.moduleEnabled}, enhancement=${systemServerSnapshot.iconEnhancementEnabled}"
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

    private fun resolveTargetIcon(context: Context, sbn: StatusBarNotification): ResolvedIcon? {
        if (!systemUiSnapshot.moduleEnabled || !systemUiSnapshot.iconEnhancementEnabled) return null
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
            statusBarDrawable = statusBarDrawable,
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
        val statusBarDrawable: Drawable,
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
