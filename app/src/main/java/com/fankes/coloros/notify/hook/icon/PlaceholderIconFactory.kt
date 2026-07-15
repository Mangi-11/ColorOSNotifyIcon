package com.fankes.coloros.notify.hook.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Built-in notification placeholder based on Material Icons `circle_notifications`.
 *
 * The vector is rendered directly because this code runs inside SystemUI, where resolving this
 * module's packaged resources would require a foreign package context. Icon geometry copyright
 * Google LLC and licensed under Apache-2.0.
 */
internal object PlaceholderIconFactory {

    private const val SIZE = 96
    private const val VIEWPORT_SIZE = 24f
    private const val MATERIAL_VISIBLE_SIZE = 20f
    private const val RULE_ICON_VISIBLE_SIZE = 22f

    private val bitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { icon ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            Canvas(icon).apply {
                val viewportScale = SIZE / VIEWPORT_SIZE
                scale(viewportScale, viewportScale)

                // Rule masks use a 22/24 optical box. Normalize Material's 20/24 silhouette to
                // that shared box so the placeholder aligns with rule icons such as Messages.
                val opticalScale = RULE_ICON_VISIBLE_SIZE / MATERIAL_VISIBLE_SIZE
                val center = VIEWPORT_SIZE / 2f
                scale(opticalScale, opticalScale, center, center)
                drawPath(materialIconPath(), paint)
            }
        }
    }

    fun createBitmap(): Bitmap = bitmap

    private fun materialIconPath(): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD

        addCircle(12f, 12f, 10f, Path.Direction.CW)

        moveTo(12f, 18.5f)
        cubicTo(11.17f, 18.5f, 10.5f, 17.83f, 10.5f, 17f)
        lineTo(13.5f, 17f)
        cubicTo(13.5f, 17.83f, 12.83f, 18.5f, 12f, 18.5f)
        close()

        moveTo(17f, 16f)
        lineTo(7f, 16f)
        lineTo(7f, 15f)
        lineTo(8f, 14f)
        lineTo(8f, 11.39f)
        cubicTo(8f, 9.27f, 9.03f, 7.47f, 11f, 7f)
        lineTo(11f, 6.5f)
        cubicTo(11f, 5.93f, 11.43f, 5.5f, 12f, 5.5f)
        cubicTo(12.57f, 5.5f, 13f, 5.93f, 13f, 6.5f)
        lineTo(13f, 7f)
        cubicTo(14.97f, 7.47f, 16f, 9.28f, 16f, 11.39f)
        lineTo(16f, 14f)
        lineTo(17f, 15f)
        close()
    }
}
