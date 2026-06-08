package com.fankes.coloros.notify.hook.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal object PlaceholderIconFactory {

    private const val SIZE = 96
    private var bitmap: Bitmap? = null

    fun createBitmap(): Bitmap = bitmap ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { icon ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val canvas = Canvas(icon)
        canvas.drawRoundRect(RectF(14f, 14f, 82f, 70f), 20f, 20f, paint)
        canvas.drawPath(
            Path().apply {
                moveTo(30f, 64f)
                lineTo(20f, 84f)
                lineTo(46f, 68f)
                close()
            },
            paint,
        )
    }.also { bitmap = it }
}
