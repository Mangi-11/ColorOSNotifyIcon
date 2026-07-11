package com.fankes.coloros.notify.hook.icon

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.drawable.toBitmap
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.runtimeFailure
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.max

internal class ThemeIconProvider(
    private val diagnostics: Diagnostics,
) {

    private val cache = object : LruCache<CacheKey, CacheEntry>(MAX_CACHE_SIZE_KIB) {
        override fun sizeOf(key: CacheKey, value: CacheEntry): Int =
            ((value.result as? CacheResult.Hit)?.bitmap?.allocationByteCount ?: 1)
                .let { bytes -> ((bytes + 1023) / 1024).coerceAtLeast(1) }
    }
    private val apiLock = Any()

    @Volatile
    private var api: UxIconApi? = null

    @Volatile
    private var apiResolved = false

    fun resolve(context: Context, sbn: StatusBarNotification): Bitmap? {
        val packageName = sbn.packageName?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val user = sbn.user
            val userId = sbn.publicUserId
            val themeGeneration = context.themeGeneration()
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val key = CacheKey(
                packageName = packageName,
                userId = userId,
                uiMode = uiMode,
                themeChanged = themeGeneration.changed,
                themeChangedFlags = themeGeneration.flags,
                uxIconConfig = themeGeneration.uxIconConfig,
            )
            cachedEntry(key)?.takeIf { !it.isExpired() }?.let { entry ->
                return (entry.result as? CacheResult.Hit)?.bitmap
            }

            val bitmap = loadThemeBitmap(context, packageName, user, userId)?.let {
                if (ThemeIconDarkEffect.isEnabled(uiMode, themeGeneration.uxIconConfig)) {
                    ThemeIconDarkEffect.apply(it)
                } else {
                    it
                }
            }
            putCacheEntry(
                key,
                CacheEntry(
                    result = bitmap?.let(CacheResult::Hit) ?: CacheResult.Miss,
                    createdAt = SystemClock.elapsedRealtime(),
                )
            )
            bitmap
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:resolve",
                message = "桌面主题图标解析失败，将保留通知原始图标",
                cause = exception,
            )
            null
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    private fun cachedEntry(key: CacheKey): CacheEntry? =
        synchronized(cache) { cache.get(key) }

    private fun putCacheEntry(key: CacheKey, entry: CacheEntry) {
        synchronized(cache) {
            cache.put(key, entry)
        }
    }

    private fun loadThemeBitmap(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): Bitmap? {
        val packageManager = context.packageManager
        val packageInfo = packageManager.resolvePackageInfo(context, packageName, user, userId) ?: return null
        val drawable = loadThemeDrawable(context, packageInfo.itemInfo, packageInfo.applicationInfo)
            ?: packageInfo.applicationInfo.takeUnless { it === packageInfo.itemInfo }?.let {
                loadThemeDrawable(context, it, packageInfo.applicationInfo)
            }
            ?: return null
        return try {
            drawable.toIconBitmap(context)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:bitmap",
                message = "桌面主题图标位图生成失败，将保留通知原始图标",
                cause = exception,
            )
            null
        }
    }

    private fun loadThemeDrawable(
        context: Context,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? {
        val uxApi = uxIconApiOrNull() ?: return null
        val uxIconManager = try {
            uxApi.constructor.newInstance(context.packageManager, context)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_instance",
                message = "创建 Oplus 主题图标管理器失败",
                cause = exception,
            )
            return null
        }

        return uxApi.loadItemIcon?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
            ?: uxApi.loadItemIconWithoutEdit?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
    }

    private fun Method.invokeDrawable(
        receiver: Any,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? = try {
        (invoke(receiver, itemInfo, applicationInfo, true) as? Drawable)?.mutate()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "theme_icon:invoke:$name",
            message = "调用 Oplus 主题图标接口失败：$name",
            cause = exception,
        )
        null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.resolvePackageInfo(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): ResolvedPackageInfo? {
        val launcherInfo = user?.let {
            try {
                context.getSystemService(LauncherApps::class.java)
                    ?.getActivityList(packageName, it)
                    ?.firstOrNull()
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "theme_icon:launcher_info",
                    message = "读取 LauncherApps 图标入口失败，尝试应用信息",
                    cause = exception,
                )
                null
            }
        }
        val applicationInfo = launcherInfo?.applicationInfo
            ?: getApplicationInfoAsUserOrNull(packageName, userId)
            ?: getApplicationInfoOrNull(packageName)
            ?: return null
        val activityInfo = launcherInfo?.componentName
            ?.let { getActivityInfoAsUserOrNull(it, userId) ?: getActivityInfoOrNull(it) }
        return ResolvedPackageInfo(
            itemInfo = activityInfo ?: applicationInfo,
            applicationInfo = applicationInfo,
        )
    }

    private fun PackageManager.getApplicationInfoAsUserOrNull(packageName: String, userId: Int): ApplicationInfo? =
        invokePackageManagerMethod(
            methodName = "getApplicationInfoAsUser",
            parameterTypes = arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            args = arrayOf(packageName, 0, userId),
        ) as? ApplicationInfo

    private fun PackageManager.getActivityInfoAsUserOrNull(componentName: ComponentName, userId: Int): ActivityInfo? =
        invokePackageManagerMethod(
            methodName = "getActivityInfoAsUser",
            parameterTypes = arrayOf(ComponentName::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            args = arrayOf(componentName, 0, userId),
        ) as? ActivityInfo

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoOrNull(packageName: String): ApplicationInfo? = try {
        getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getActivityInfoOrNull(componentName: ComponentName): ActivityInfo? = try {
        getActivityInfo(componentName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun PackageManager.invokePackageManagerMethod(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Any? = try {
        javaClass.getMethod(methodName, *parameterTypes).invoke(this, *args)
    } catch (exception: NoSuchMethodException) {
        diagnostics.memberMissing(
            scope = "theme_icon:package_manager:$methodName",
            message = "PackageManager.$methodName 精确签名不存在，使用公开 API 回退",
            cause = exception,
        )
        null
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "theme_icon:package_manager:$methodName",
            message = "调用 PackageManager.$methodName 失败，使用公开 API 回退",
            cause = exception,
        )
        null
    }

    private fun uxIconApiOrNull(): UxIconApi? {
        if (apiResolved) return api
        synchronized(apiLock) {
            if (!apiResolved) {
                api = buildUxIconApi()
                apiResolved = true
            }
        }
        return api
    }

    @SuppressLint("PrivateApi") // ColorOS exposes this framework extension only to system code.
    private fun buildUxIconApi(): UxIconApi? {
        val clazz = try {
            Class.forName(UX_ICON_PACKAGE_MANAGER_EXT)
        } catch (exception: ClassNotFoundException) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_class",
                message = "未找到 Oplus 主题图标接口，将保留通知原始图标",
                cause = exception,
            )
            return null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_class",
                message = "加载 Oplus 主题图标接口失败",
                cause = exception,
            )
            return null
        }
        val constructor = try {
            clazz.getConstructor(PackageManager::class.java, Context::class.java)
        } catch (exception: NoSuchMethodException) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_constructor",
                message = "Oplus 主题图标接口构造函数签名不匹配",
                cause = exception,
            )
            return null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_constructor",
                message = "读取 Oplus 主题图标接口构造函数失败",
                cause = exception,
            )
            return null
        }
        val loadItemIcon: Method?
        val loadItemIconWithoutEdit: Method?
        try {
            loadItemIcon = clazz.findUxIconMethod("loadItemIcon")
            loadItemIconWithoutEdit = clazz.findUxIconMethod("loadItemIconWithoutEdit")
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_methods",
                message = "读取 Oplus 主题图标加载方法失败",
                cause = exception,
            )
            return null
        }
        if (loadItemIcon == null && loadItemIconWithoutEdit == null) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_methods",
                message = "Oplus 主题图标加载方法签名不匹配",
            )
            return null
        }
        return UxIconApi(constructor, loadItemIcon, loadItemIconWithoutEdit)
    }

    private fun Class<*>.findUxIconMethod(name: String): Method? = try {
        getDeclaredMethod(
            name,
            PackageItemInfo::class.java,
            ApplicationInfo::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).takeIf { Drawable::class.java.isAssignableFrom(it.returnType) }
            ?.apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun Context.themeGeneration(): ThemeGeneration {
        val extraConfiguration = resources.configuration.oplusExtraConfigurationOrNull()
        return ThemeGeneration(
            changed = extraConfiguration?.longMember("mThemeChanged", "getThemeChanged") ?: 0L,
            flags = extraConfiguration?.longMember("mThemeChangedFlags", "getThemeChangedFlags") ?: 0L,
            uxIconConfig = extraConfiguration?.longMember("mUxIconConfig", "getUxIconConfig") ?: -1L,
        )
    }

    private fun Configuration.oplusExtraConfigurationOrNull(): Any? {
        val accessor = try {
            javaClass.getMethod("getOplusExtraConfiguration")
        } catch (_: NoSuchMethodException) {
            null
        }
        if (
            accessor != null &&
            !Modifier.isStatic(accessor.modifiers) &&
            accessor.returnType != Void.TYPE
        ) {
            try {
                return accessor.invoke(this)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "theme_icon:theme_generation",
                    message = "读取 Oplus 主题代次失败，尝试字段回退",
                    cause = exception,
                )
            }
        } else if (accessor != null) {
            diagnostics.memberMissing(
                scope = "theme_icon:extra_configuration_accessor",
                message = "Oplus 主题代次访问器不是预期的实例非 void 方法，尝试字段回退",
            )
        }
        return try {
            javaClass.getDeclaredField("mOplusExtraConfiguration")
                .apply { isAccessible = true }
                .get(this)
        } catch (exception: NoSuchFieldException) {
            diagnostics.memberMissing(
                scope = "theme_icon:extra_configuration",
                message = "未找到 Oplus 主题代次成员，使用短期缓存",
                cause = exception,
            )
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:extra_configuration",
                message = "读取 Oplus 主题配置失败，使用短期缓存",
                cause = exception,
            )
            null
        }
    }

    private fun Any.longMember(fieldName: String, methodName: String): Long? {
        try {
            javaClass.getDeclaredField(fieldName)
                .apply { isAccessible = true }
                .get(this)
                .toLongOrNull()
                ?.let { return it }
        } catch (_: NoSuchFieldException) {
            // Try the known accessor below.
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:theme_generation:$fieldName",
                message = "读取 Oplus 主题代次字段失败，尝试访问器",
                cause = exception,
            )
        }
        val accessor = try {
            javaClass.getMethod(methodName)
        } catch (_: NoSuchMethodException) {
            null
        }?.takeIf {
            it.returnType == Long::class.javaPrimitiveType || Number::class.java.isAssignableFrom(it.returnType)
        } ?: run {
            diagnostics.memberMissing(
                scope = "theme_icon:theme_generation:$fieldName",
                message = "未找到 Oplus 主题代次成员，使用短期缓存",
            )
            return null
        }
        return try {
            accessor.invoke(this).toLongOrNull()
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:theme_generation:$methodName",
                message = "读取 Oplus 主题代次字段失败，使用短期缓存",
                cause = exception,
            )
            null
        }
    }

    private fun Any?.toLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        else -> null
    }

    // UserHandle#getIdentifier is hidden from the public SDK used by this module.
    @Suppress("DEPRECATION")
    private val StatusBarNotification.publicUserId: Int
        get() = userId

    private fun Drawable.toIconBitmap(context: Context): Bitmap {
        val requestedTargetSize = context.resources
            .getDimensionPixelSize(android.R.dimen.app_icon_size)
            .coerceAtLeast(1)
        val requestedRenderSize = max(
            requestedTargetSize,
            max(intrinsicWidth.coerceAtLeast(0), intrinsicHeight.coerceAtLeast(0)),
        )
        val renderSize = cappedSquareRenderSize(
            targetSize = requestedTargetSize,
            intrinsicWidth = intrinsicWidth,
            intrinsicHeight = intrinsicHeight,
            maxDimension = MAX_THEME_RENDER_DIMENSION,
        )
        if (requestedRenderSize > renderSize) {
            diagnostics.report(
                level = DiagnosticLevel.Warning,
                event = DiagnosticEvent.IconRenderClamped,
                message = "主题 Drawable 尺寸异常，已限制安全渲染范围",
                attributes = mapOf(
                    "limit" to MAX_THEME_RENDER_DIMENSION,
                    "requested" to requestedRenderSize,
                ),
                occurrence = OccurrencePolicy.Once("theme_icon:render_size_capped"),
            )
        }
        val rendered = toBitmap(width = renderSize, height = renderSize, config = Bitmap.Config.ARGB_8888)
        return rendered.trimTransparentPadding(requestedTargetSize.coerceAtMost(MAX_THEME_RENDER_DIMENSION))
    }

    private fun Bitmap.trimTransparentPadding(targetSize: Int): Bitmap {
        val visibleBounds = visibleBoundsOrNull() ?: return scaleToTarget(targetSize)
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val contentSize = (targetSize * ICON_CONTENT_RATIO).toInt().coerceIn(1, targetSize)
        val scale = minOf(
            contentSize.toFloat() / visibleBounds.width().toFloat(),
            contentSize.toFloat() / visibleBounds.height().toFloat(),
        )
        val drawWidth = visibleBounds.width() * scale
        val drawHeight = visibleBounds.height() * scale
        val left = (targetSize - drawWidth) / 2f
        val top = (targetSize - drawHeight) / 2f
        Canvas(result).drawBitmap(
            this,
            visibleBounds,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private fun Bitmap.scaleToTarget(targetSize: Int): Bitmap {
        if (width == targetSize && height == targetSize) return this
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            this,
            null,
            RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private fun Bitmap.visibleBoundsOrNull(): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(this[x, y]) <= OPAQUE_ALPHA_THRESHOLD) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun CacheEntry.isExpired(): Boolean =
        SystemClock.elapsedRealtime() - createdAt > CACHE_TTL_MS

    private data class ResolvedPackageInfo(
        val itemInfo: PackageItemInfo,
        val applicationInfo: ApplicationInfo,
    )

    private data class UxIconApi(
        val constructor: Constructor<*>,
        val loadItemIcon: Method?,
        val loadItemIconWithoutEdit: Method?,
    )

    private data class CacheKey(
        val packageName: String,
        val userId: Int,
        val uiMode: Int,
        val themeChanged: Long,
        val themeChangedFlags: Long,
        val uxIconConfig: Long,
    )

    private data class ThemeGeneration(
        val changed: Long,
        val flags: Long,
        val uxIconConfig: Long,
    )

    private data class CacheEntry(
        val result: CacheResult,
        val createdAt: Long,
    )

    private sealed class CacheResult {
        data class Hit(val bitmap: Bitmap) : CacheResult()
        object Miss : CacheResult()
    }

    private companion object {
        const val UX_ICON_PACKAGE_MANAGER_EXT = "android.app.UxIconPackageManagerExt"
        const val ICON_CONTENT_RATIO = 1f
        const val OPAQUE_ALPHA_THRESHOLD = 8
        const val CACHE_TTL_MS = 10_000L
        const val MAX_CACHE_SIZE_KIB = 4 * 1024
        const val MAX_THEME_RENDER_DIMENSION = 512
    }
}
