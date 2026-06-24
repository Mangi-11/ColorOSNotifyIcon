package com.fankes.coloros.notify.hook.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs

/**
 * 从 AOSP 拆出的灰度位图判定逻辑，用于替代 ColorOS 16 上不稳定的彩色图标判断。
 */
object IconBitmapClassifier {

    private const val MAX_CACHE_ENTRIES = 256

    private val lock = Any()
    private val cachedBitmapGrayscales = LruCache<Int, Boolean>(MAX_CACHE_ENTRIES)
    private var tempBuffer = intArrayOf(0)
    private var tempCompactBitmap: Bitmap? = null
    private var tempCompactBitmapCanvas: Canvas? = null
    private var tempCompactBitmapPaint: Paint? = null
    private val tempMatrix = Matrix()

    private inline fun safeFalse(block: () -> Boolean) = try {
        block()
    } catch (_: Exception) {
        false
    }

    fun isGrayscaleDrawable(drawable: Drawable) = safeFalse {
        when (drawable) {
            is BitmapDrawable -> isGrayscaleBitmap(drawable.bitmap)
            is AnimationDrawable -> drawable.numberOfFrames > 0 && isGrayscaleBitmap(drawable.getFrame(0).toBitmap())
            is VectorDrawable -> true
            else -> isGrayscaleBitmap(drawable.toBitmap())
        }
    }

    private fun isGrayscaleBitmap(bitmap: Bitmap): Boolean = synchronized(lock) {
        cachedBitmapGrayscales.get(bitmap.generationId) ?: run {
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
                    cachedBitmapGrayscales.put(bitmap.generationId, false)
                    return@run false
                }
            }
            cachedBitmapGrayscales.put(bitmap.generationId, true)
            true
        }
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
}
