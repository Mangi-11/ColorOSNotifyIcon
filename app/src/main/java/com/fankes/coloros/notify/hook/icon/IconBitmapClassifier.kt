package com.fankes.coloros.notify.hook.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs

/**
 * 判断图标是否可作为单色遮罩统一着色。
 *
 * Android 通知图标的可见颜色并不要求是灰色；应用可以用任意单一色相绘制 vector，
 * SystemUI 最终只使用它的轮廓与透明度。这里保留 AOSP 灰度图标的兼容性，同时也接受
 * 单一非灰色颜色，避免把合法的粉色、蓝色等通知图标误判为多彩图标。
 */
internal object IconBitmapClassifier {

    private const val MAX_CACHE_ENTRIES = 256

    private val lock = Any()
    private val cachedBitmapMonochromes = LruCache<Int, Boolean>(MAX_CACHE_ENTRIES)
    private var tempBuffer = intArrayOf(0)
    private var tempCompactBitmap: Bitmap? = null
    private var tempCompactBitmapCanvas: Canvas? = null
    private var tempCompactBitmapPaint: Paint? = null
    private val tempMatrix = Matrix()

    fun isMonochromeDrawable(drawable: Drawable): Boolean = when (drawable) {
        is BitmapDrawable -> isMonochromeBitmap(drawable.bitmap)
        is AnimationDrawable -> drawable.numberOfFrames > 0 && isMonochromeRendered(drawable.getFrame(0))
        else -> isMonochromeRendered(drawable)
    }

    private fun isMonochromeRendered(drawable: Drawable): Boolean {
        val size = fitWithinRenderBounds(
            intrinsicWidth = drawable.intrinsicWidth,
            intrinsicHeight = drawable.intrinsicHeight,
            maxDimension = MAX_RENDER_DIMENSION,
        )
        return isMonochromeBitmap(
            drawable.toBitmap(
                width = size.width,
                height = size.height,
                config = Bitmap.Config.ARGB_8888,
            )
        )
    }

    private fun isMonochromeBitmap(bitmap: Bitmap): Boolean = synchronized(lock) {
        cachedBitmapMonochromes.get(bitmap.generationId) ?: run {
            var height = bitmap.height
            var width = bitmap.width
            var pixelSource: Bitmap = bitmap
            if (height > MAX_RENDER_DIMENSION || width > MAX_RENDER_DIMENSION) {
                if (tempCompactBitmap == null) {
                    tempCompactBitmap = createBitmap(
                        MAX_RENDER_DIMENSION,
                        MAX_RENDER_DIMENSION,
                        Bitmap.Config.ARGB_8888,
                    )
                        .also { tempCompactBitmapCanvas = Canvas(it) }
                    tempCompactBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true }
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
            isMonochromePixelBuffer(tempBuffer, size).also { result ->
                cachedBitmapMonochromes.put(bitmap.generationId, result)
            }
        }
    }

    private fun ensureBufferSize(size: Int) {
        if (tempBuffer.size < size) tempBuffer = IntArray(size)
    }

    private const val MAX_RENDER_DIMENSION = 64
}

internal fun isMonochromePixelBuffer(
    pixels: IntArray,
    size: Int = pixels.size,
): Boolean {
    require(size in 0..pixels.size) { "size must be within the pixel buffer" }
    var referenceColor: Int? = null
    for (index in 0 until size) {
        val color = pixels[index]
        if (color.alpha < MIN_VISIBLE_ALPHA || color.isNeutral) continue
        val reference = referenceColor
        if (reference == null) {
            referenceColor = color
        } else if (!color.isNear(reference)) {
            return false
        }
    }
    if (referenceColor == null) return true

    // A chromatic icon remains monochrome only when every visible pixel belongs to that
    // color. An opaque gray/black detail alongside it is a genuine second color.
    return (0 until size).none { index ->
        val color = pixels[index]
        color.alpha >= MIN_VISIBLE_ALPHA && color.isNeutral
    }
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
