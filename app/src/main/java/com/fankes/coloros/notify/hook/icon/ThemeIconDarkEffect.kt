package com.fankes.coloros.notify.hook.icon

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap

/** Mirrors ColorOS Launcher's dark-mode treatment for themed app icons. */
internal object ThemeIconDarkEffect {

    private const val CLASSIC_THEME = 2
    private const val DARK_THEME = 3
    private const val THEME_SHIFT = 4
    private const val THEME_MASK = 0xF
    private const val DARK_MODE_ICON_SHIFT = 61
    private const val COLOR_MULTIPLIER = 0xD6D6D6

    fun isEnabled(uiMode: Int, uxIconConfig: Long): Boolean {
        if ((uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES) return false
        if (uxIconConfig == -1L) return false

        val theme = ((uxIconConfig ushr THEME_SHIFT) and THEME_MASK.toLong()).toInt()
        val darkModeIcon = ((uxIconConfig ushr DARK_MODE_ICON_SHIFT) and 1L) == 1L
        return (theme == CLASSIC_THEME && darkModeIcon) || theme == DARK_THEME
    }

    fun apply(bitmap: Bitmap): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = LightingColorFilter(COLOR_MULTIPLIER, 0)
        }
        Canvas(result).drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    fun apply(color: Int): Int =
        (color and ALPHA_MASK) or
            (darken(color ushr RED_SHIFT and CHANNEL_MASK) shl RED_SHIFT) or
            (darken(color ushr GREEN_SHIFT and CHANNEL_MASK) shl GREEN_SHIFT) or
            darken(color and CHANNEL_MASK)

    private fun darken(channel: Int): Int = (channel * 0xD6) / 0xFF

    private const val ALPHA_MASK = -0x1000000
    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
