package com.fankes.coloros.notify.hook.icon

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
import androidx.core.graphics.drawable.toBitmap
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal object ThemeIconProvider {

    private const val UX_ICON_PACKAGE_MANAGER_EXT = "android.app.UxIconPackageManagerExt"
    private const val ICON_CONTENT_RATIO = 1f
    private const val OPAQUE_ALPHA_THRESHOLD = 8
    private const val CACHE_TTL_MS = 10_000L
    private const val MAX_CACHE_SIZE_KIB = 4 * 1024

    private val cache = object : LruCache<CacheKey, CacheEntry>(MAX_CACHE_SIZE_KIB) {
        override fun sizeOf(key: CacheKey, value: CacheEntry): Int =
            ((value.result as? CacheResult.Hit)?.bitmap?.allocationByteCount ?: 1)
                .let { bytes -> ((bytes + 1023) / 1024).coerceAtLeast(1) }
    }
    private val onceLogs = ConcurrentHashMap.newKeySet<String>()
    private val apiLock = Any()

    @Volatile
    private var warningLogger: ((key: String, message: String, throwable: Throwable?) -> Unit)? = null

    @Volatile
    private var api: UxIconApi? = null

    @Volatile
    private var apiResolved = false

    fun setWarningLogger(logger: (key: String, message: String, throwable: Throwable?) -> Unit) {
        warningLogger = logger
    }

    fun resolve(context: Context, sbn: StatusBarNotification): Bitmap? {
        val packageName = sbn.packageName?.takeIf { it.isNotBlank() } ?: return null
        val user = runCatching { sbn.user }.getOrNull()
        val userId = user.identifierOrDefault()
        val themeGeneration = context.themeGeneration()
        val key = CacheKey(
            packageName = packageName,
            userId = userId,
            uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            themeChanged = themeGeneration.changed,
            themeChangedFlags = themeGeneration.flags,
        )
        cachedEntry(key)?.takeIf { !it.isExpired() }?.let { entry ->
            return (entry.result as? CacheResult.Hit)?.bitmap
        }

        val bitmap = loadThemeBitmap(context, packageName, user, userId)
        putCacheEntry(
            key,
            CacheEntry(
                result = bitmap?.let(CacheResult::Hit) ?: CacheResult.Miss,
                createdAt = SystemClock.elapsedRealtime(),
            )
        )
        return bitmap
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
        return runCatching { drawable.toIconBitmap(context) }
            .onFailure { warnOnce("theme.icon.bitmap", "桌面主题图标位图生成失败，将回退原始通知图标", it) }
            .getOrNull()
    }

    private fun loadThemeDrawable(
        context: Context,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? {
        val uxApi = uxIconApiOrNull() ?: return null
        val uxIconManager = runCatching {
            uxApi.constructor.newInstance(context.packageManager, context)
        }.onFailure {
            warnOnce("theme.icon.api.instance", "创建 Oplus 主题图标管理器失败", it)
        }.getOrNull() ?: return null

        return uxApi.loadItemIcon?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
            ?: uxApi.loadItemIconWithoutEdit?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
    }

    private fun Method.invokeDrawable(
        receiver: Any,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? = runCatching {
        invoke(receiver, itemInfo, applicationInfo, true) as? Drawable
    }.onFailure {
        warnOnce("theme.icon.invoke.$name", "调用 Oplus 主题图标接口失败：$name", it)
    }.getOrNull()?.mutate()

    @Suppress("DEPRECATION")
    private fun PackageManager.resolvePackageInfo(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): ResolvedPackageInfo? {
        val launcherInfo = user?.let {
            runCatching {
                context.getSystemService(LauncherApps::class.java)
                    ?.getActivityList(packageName, it)
                    ?.firstOrNull()
            }.getOrNull()
        }
        val applicationInfo = launcherInfo?.applicationInfo
            ?: getApplicationInfoAsUserOrNull(packageName, userId)
            ?: runCatching { getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        val activityInfo = launcherInfo?.componentName
            ?.let { getActivityInfoAsUserOrNull(it, userId) ?: runCatching { getActivityInfo(it, 0) }.getOrNull() }
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

    private fun PackageManager.invokePackageManagerMethod(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Any? = runCatching {
        javaClass.getMethod(methodName, *parameterTypes).invoke(this, *args)
    }.getOrNull()

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

    private fun buildUxIconApi(): UxIconApi? = runCatching {
        val clazz = Class.forName(UX_ICON_PACKAGE_MANAGER_EXT)
        val constructor = clazz.getConstructor(PackageManager::class.java, Context::class.java)
        UxIconApi(
            constructor = constructor,
            loadItemIcon = clazz.findUxIconMethod("loadItemIcon"),
            loadItemIconWithoutEdit = clazz.findUxIconMethod("loadItemIconWithoutEdit"),
        )
    }.onFailure {
        warnOnce("theme.icon.api", "未找到 Oplus 主题图标接口，将回退原始通知图标", it)
    }.getOrNull()

    private fun Class<*>.findUxIconMethod(name: String): Method? =
        runCatching {
            getDeclaredMethod(
                name,
                PackageItemInfo::class.java,
                ApplicationInfo::class.java,
                Boolean::class.javaPrimitiveType!!,
            ).apply { isAccessible = true }
        }.getOrNull()

    private fun Context.themeGeneration(): ThemeGeneration {
        val extraConfiguration = resources.configuration.oplusExtraConfigurationOrNull()
        return ThemeGeneration(
            changed = extraConfiguration?.longMember("mThemeChanged", "getThemeChanged") ?: 0L,
            flags = extraConfiguration?.longMember("mThemeChangedFlags", "getThemeChangedFlags") ?: 0L,
        )
    }

    private fun Configuration.oplusExtraConfigurationOrNull(): Any? =
        runCatching {
            javaClass.methods
                .firstOrNull { it.name == "getOplusExtraConfiguration" && it.parameterTypes.isEmpty() }
                ?.invoke(this)
        }.getOrNull()
            ?: runCatching {
                javaClass.getDeclaredField("mOplusExtraConfiguration").apply { isAccessible = true }.get(this)
            }.getOrNull()

    private fun Any.longMember(fieldName: String, methodName: String): Long? =
        runCatching {
            javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(this).toLongOrNull()
        }.getOrNull()
            ?: runCatching {
                javaClass.methods
                    .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                    ?.invoke(this)
                    .toLongOrNull()
            }.getOrNull()

    private fun Any?.toLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        else -> null
    }

    private fun UserHandle?.identifierOrDefault(): Int =
        runCatching {
            this?.javaClass?.getMethod("getIdentifier")?.invoke(this) as? Int
        }.getOrNull() ?: 0

    private fun Drawable.toIconBitmap(context: Context): Bitmap {
        val targetSize = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size).coerceAtLeast(1)
        val renderSize = max(
            targetSize,
            max(intrinsicWidth.takeIf { it > 0 } ?: 0, intrinsicHeight.takeIf { it > 0 } ?: 0),
        )
        val rendered = toBitmap(width = renderSize, height = renderSize, config = Bitmap.Config.ARGB_8888)
        return rendered.trimTransparentPadding(targetSize)
    }

    private fun Bitmap.trimTransparentPadding(targetSize: Int): Bitmap {
        val visibleBounds = visibleBoundsOrNull() ?: return scaleToTarget(targetSize)
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
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
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
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
                if (Color.alpha(getPixel(x, y)) <= OPAQUE_ALPHA_THRESHOLD) continue
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

    private fun warnOnce(key: String, message: String, throwable: Throwable? = null) {
        if (!onceLogs.add(key)) return
        warningLogger?.invoke(key, message, throwable)
    }

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
    )

    private data class ThemeGeneration(
        val changed: Long,
        val flags: Long,
    )

    private data class CacheEntry(
        val result: CacheResult,
        val createdAt: Long,
    )

    private sealed class CacheResult {
        data class Hit(val bitmap: Bitmap) : CacheResult()
        object Miss : CacheResult()
    }
}
