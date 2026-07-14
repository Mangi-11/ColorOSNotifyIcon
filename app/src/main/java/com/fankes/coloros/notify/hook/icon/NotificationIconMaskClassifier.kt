package com.fankes.coloros.notify.hook.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 判断图标能否作为 Android 通知图标的 Alpha 遮罩。
 *
 * SystemUI 会用统一颜色替换通知图标的 RGB，只保留 Alpha 轮廓。前景不必是灰色，
 * 但它必须是灰阶或单一色相；多种独立色彩说明它不是可直接着色的通知遮罩。
 * “有透明像素”本身也不足以证明它是通知遮罩，带透明圆角的启动图标同样满足这个条件。
 * 这里额外排除占据画布大部分且内部致密的背景底板。
 * AdaptiveIconDrawable 属于桌面启动图标模型，直接视为不兼容。
 */
internal object NotificationIconMaskClassifier {

    private data class CachedCompatibility(
        val generationId: Int,
        val isCompatible: Boolean,
    )

    private val lock = Any()
    // Do not retain notification bitmaps for the lifetime of SystemUI. The generation id still
    // invalidates a cached result when a mutable bitmap changes in place.
    private val cachedBitmapCompatibility = WeakHashMap<Bitmap, CachedCompatibility>()
    private var tempBuffer = intArrayOf(0)
    private var tempCompactBitmap: Bitmap? = null
    private var tempCompactBitmapCanvas: Canvas? = null
    private var tempCompactBitmapPaint: Paint? = null
    private val tempMatrix = Matrix()

    fun classify(drawable: Drawable): NotificationIconMaskCompatibility = when (drawable) {
        is AdaptiveIconDrawable -> NotificationIconMaskCompatibility.Incompatible
        // Reading pixels from a hardware bitmap throws. Unknown delegates to SystemUI instead of
        // incorrectly treating an unreadable bitmap as either a mask or a colored launcher icon.
        is BitmapDrawable -> drawable.bitmap
            .takeUnless { it.config == Bitmap.Config.HARDWARE }
            ?.let(::isMaskCompatible)
            ?.toCompatibility()
            ?: NotificationIconMaskCompatibility.Unknown
        is AnimationDrawable ->
            if (drawable.numberOfFrames > 0) {
                classify(drawable.getFrame(0))
            } else {
                NotificationIconMaskCompatibility.Incompatible
            }
        else -> isMaskCompatibleRendered(drawable).toCompatibility()
    }

    private fun isMaskCompatibleRendered(drawable: Drawable): Boolean {
        val size = fitWithinRenderBounds(
            intrinsicWidth = drawable.intrinsicWidth,
            intrinsicHeight = drawable.intrinsicHeight,
            maxDimension = MAX_RENDER_DIMENSION,
        )
        return isMaskCompatible(
            drawable.toBitmap(
                width = size.width,
                height = size.height,
                config = Bitmap.Config.ARGB_8888,
            )
        )
    }

    private fun isMaskCompatible(bitmap: Bitmap): Boolean = synchronized(lock) {
        cachedBitmapCompatibility[bitmap]
            ?.takeIf { it.generationId == bitmap.generationId }
            ?.isCompatible
            ?: run {
                var height = bitmap.height
                var width = bitmap.width
                var pixelSource: Bitmap = bitmap
                if (height > MAX_RENDER_DIMENSION || width > MAX_RENDER_DIMENSION) {
                    if (tempCompactBitmap == null) {
                        tempCompactBitmap = createBitmap(
                            MAX_RENDER_DIMENSION,
                            MAX_RENDER_DIMENSION,
                            Bitmap.Config.ARGB_8888,
                        ).also { tempCompactBitmapCanvas = Canvas(it) }
                        tempCompactBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                            isFilterBitmap = true
                        }
                    }
                    val compactSize = fitWithinRenderBounds(width, height, MAX_RENDER_DIMENSION)
                    tempMatrix.reset()
                    tempMatrix.setScale(
                        compactSize.width.toFloat() / width,
                        compactSize.height.toFloat() / height,
                        0f,
                        0f,
                    )
                    tempCompactBitmapCanvas?.drawColor(0, PorterDuff.Mode.SRC)
                    tempCompactBitmapCanvas?.drawBitmap(bitmap, tempMatrix, tempCompactBitmapPaint)
                    height = compactSize.height
                    width = compactSize.width
                    pixelSource = tempCompactBitmap ?: bitmap
                }
                val size = height * width
                ensureBufferSize(size)
                pixelSource.getPixels(tempBuffer, 0, width, 0, 0, width, height)
                hasUsableNotificationMask(tempBuffer, width, height).also { result ->
                    cachedBitmapCompatibility[bitmap] =
                        CachedCompatibility(bitmap.generationId, result)
                }
            }
    }

    private fun ensureBufferSize(size: Int) {
        if (tempBuffer.size < size) tempBuffer = IntArray(size)
    }

    private const val MAX_RENDER_DIMENSION = 64
}

internal enum class NotificationIconMaskCompatibility {
    Compatible,
    Incompatible,
    Unknown,
}

private fun Boolean.toCompatibility(): NotificationIconMaskCompatibility =
    if (this) {
        NotificationIconMaskCompatibility.Compatible
    } else {
        NotificationIconMaskCompatibility.Incompatible
    }

internal fun hasUsableNotificationMask(
    pixels: IntArray,
    width: Int,
    height: Int,
): Boolean {
    require(width >= 0 && height >= 0) { "dimensions must not be negative" }
    val size = width.toLong() * height.toLong()
    require(size <= pixels.size) { "dimensions must fit within the pixel buffer" }
    if (size == 0L) return false

    var visiblePixels = 0
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var referenceChromaticColor: Int? = null
    var hasNeutralColor = false
    for (index in 0 until size.toInt()) {
        val color = pixels[index]
        if (color.alpha < MIN_VISIBLE_ALPHA) continue
        visiblePixels++
        val x = index % width
        val y = index / width
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y

        if (color.isNeutral) {
            hasNeutralColor = true
        } else {
            val reference = referenceChromaticColor
            if (reference == null) {
                referenceChromaticColor = color
            } else if (!color.isNear(reference)) {
                return false
            }
        }
    }
    if (visiblePixels == 0 || visiblePixels == size.toInt()) return false
    if (referenceChromaticColor != null && hasNeutralColor) return false

    val boundsWidth = maxX - minX + 1
    val boundsHeight = maxY - minY + 1
    val spansMostOfCanvas =
        boundsWidth.toLong() * PERCENT_SCALE >= width.toLong() * PLATE_MIN_SPAN_PERCENT &&
            boundsHeight.toLong() * PERCENT_SCALE >=
            height.toLong() * PLATE_MIN_SPAN_PERCENT
    val denselyFillsBounds =
        visiblePixels.toLong() * PERCENT_SCALE >=
            boundsWidth.toLong() * boundsHeight.toLong() * PLATE_MIN_FILL_PERCENT

    return !(spansMostOfCanvas && denselyFillsBounds)
}

private val Int.alpha: Int get() = this ushr 24 and 0xFF
private val Int.red: Int get() = this ushr 16 and 0xFF
private val Int.green: Int get() = this ushr 8 and 0xFF
private val Int.blue: Int get() = this and 0xFF

private val Int.isNeutral: Boolean
    get() = abs(red - green) < CHANNEL_TOLERANCE &&
        abs(red - blue) < CHANNEL_TOLERANCE &&
        abs(green - blue) < CHANNEL_TOLERANCE

private fun Int.isNear(other: Int): Boolean =
    abs(red - other.red) < CHANNEL_TOLERANCE &&
        abs(green - other.green) < CHANNEL_TOLERANCE &&
        abs(blue - other.blue) < CHANNEL_TOLERANCE

private const val MIN_VISIBLE_ALPHA = 50
private const val CHANNEL_TOLERANCE = 20
private const val PERCENT_SCALE = 100L
private const val PLATE_MIN_SPAN_PERCENT = 75L
private const val PLATE_MIN_FILL_PERCENT = 70L
