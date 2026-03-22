package com.fankes.coloros.notify.utils.tool

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.ArrayMap
import androidx.core.graphics.drawable.toBitmap
import com.fankes.coloros.notify.utils.factory.safeOfFalse
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 从 AOSP 拆出的灰度位图判定逻辑，用于替代 ColorOS 16 上不稳定的彩色图标判断。
 */
object BitmapCompatTool {

    private val cachedBitmapGrayscales = ArrayMap<Int, Boolean>()
    private val normalizedBitmapCache = ArrayMap<String, Bitmap>()
    private var tempBuffer = intArrayOf(0)
    private var tempCompactBitmap: Bitmap? = null
    private var tempCompactBitmapCanvas: Canvas? = null
    private var tempCompactBitmapPaint: Paint? = null
    private val tempMatrix = Matrix()

    fun isGrayscaleDrawable(drawable: Drawable) = safeOfFalse {
        when (drawable) {
            is BitmapDrawable -> isGrayscaleBitmap(drawable.bitmap)
            is AnimationDrawable -> drawable.numberOfFrames > 0 && isGrayscaleBitmap(drawable.getFrame(0).toBitmap())
            is VectorDrawable -> true
            else -> isGrayscaleBitmap(drawable.toBitmap())
        }
    }

    fun normalizeIconBitmap(
        bitmap: Bitmap,
        outputSize: Int = 72,
        contentScale: Float = 0.84f,
        alphaThreshold: Int = 16,
    ): Bitmap {
        val safeOutputSize = outputSize.coerceAtLeast(1)
        val cacheKey = "${bitmap.generationId}:$safeOutputSize:${(contentScale * 1000).roundToInt()}:$alphaThreshold"
        normalizedBitmapCache[cacheKey]?.let { return it }
        val sourceBounds = findOpaqueBounds(bitmap, alphaThreshold) ?: return bitmap
        val sourceWidth = sourceBounds.width()
        val sourceHeight = sourceBounds.height()
        if (
            bitmap.width == safeOutputSize &&
            bitmap.height == safeOutputSize &&
            sourceBounds.left == 0 &&
            sourceBounds.top == 0 &&
            sourceBounds.right == bitmap.width &&
            sourceBounds.bottom == bitmap.height
        ) {
            normalizedBitmapCache[cacheKey] = bitmap
            return bitmap
        }
        val targetContentSize = (safeOutputSize * contentScale.coerceIn(0.5f, 1f)).roundToInt().coerceAtLeast(1)
        val scale = minOf(
            targetContentSize.toFloat() / sourceWidth.coerceAtLeast(1),
            targetContentSize.toFloat() / sourceHeight.coerceAtLeast(1),
        )
        val destWidth = sourceWidth * scale
        val destHeight = sourceHeight * scale
        val destLeft = (safeOutputSize - destWidth) / 2f
        val destTop = (safeOutputSize - destHeight) / 2f
        val output = Bitmap.createBitmap(safeOutputSize, safeOutputSize, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            bitmap,
            sourceBounds,
            RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isFilterBitmap = true
                isDither = false
            }
        )
        normalizedBitmapCache[cacheKey] = output
        return output
    }

    private fun isGrayscaleBitmap(bitmap: Bitmap) =
        cachedBitmapGrayscales[bitmap.generationId] ?: run {
            var height = bitmap.height
            var width = bitmap.width
            var pixelSource: Bitmap = bitmap
            if (height > 64 || width > 64) {
                if (tempCompactBitmap == null) {
                    tempCompactBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                        .also { tempCompactBitmapCanvas = Canvas(it) }
                    tempCompactBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true }
                }
                tempMatrix.reset()
                tempMatrix.setScale(64f / width, 64f / height, 0f, 0f)
                tempCompactBitmapCanvas?.drawColor(0, PorterDuff.Mode.SRC)
                tempCompactBitmapCanvas?.drawBitmap(bitmap, tempMatrix, tempCompactBitmapPaint)
                height = 64
                width = 64
                pixelSource = tempCompactBitmap ?: bitmap
            }
            val size = height * width
            ensureBufferSize(size)
            pixelSource.getPixels(tempBuffer, 0, width, 0, 0, width, height)
            for (index in 0 until size) {
                if (!isGrayscaleColor(tempBuffer[index])) {
                    cachedBitmapGrayscales[bitmap.generationId] = false
                    return@run false
                }
            }
            cachedBitmapGrayscales[bitmap.generationId] = true
            true
        }

    private fun isGrayscaleColor(color: Int): Boolean {
        if (color shr 24 and 255 < 50) return true
        val red = color shr 16 and 255
        val green = color shr 8 and 255
        val blue = color and 255
        return abs(red - green) < 20 && abs(red - blue) < 20 && abs(green - blue) < 20
    }

    private fun ensureBufferSize(size: Int) {
        if (tempBuffer.size < size) tempBuffer = IntArray(size)
    }

    private fun findOpaqueBounds(bitmap: Bitmap, alphaThreshold: Int): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        ensureBufferSize(width * height)
        bitmap.getPixels(tempBuffer, 0, width, 0, 0, width, height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        val size = width * height
        for (index in 0 until size) {
            val alpha = tempBuffer[index] ushr 24 and 255
            if (alpha <= alphaThreshold) continue
            val x = index % width
            val y = index / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (maxX < minX || maxY < minY) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }
}
