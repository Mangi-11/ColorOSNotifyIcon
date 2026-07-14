package com.fankes.coloros.notify.hook.icon

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconDarkEffectTest {

    @Test
    fun `rectangle theme follows ColorOS dark icon flag`() {
        val rectangleTheme = 2L shl 4

        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, rectangleTheme))
        assertTrue(
            ThemeIconDarkEffect.isEnabled(
                Configuration.UI_MODE_NIGHT_YES,
                rectangleTheme or (1L shl 61),
            )
        )
    }

    @Test
    fun `effect is disabled outside night mode`() {
        val enabledRectangleTheme = (2L shl 4) or (1L shl 61)

        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_NO, enabledRectangleTheme))
        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, -1L))
    }

    @Test
    fun `custom theme is never mistaken for a dark theme`() {
        val customTheme = 3L shl 4

        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, customTheme))
        assertFalse(
            ThemeIconDarkEffect.isEnabled(
                Configuration.UI_MODE_NIGHT_YES,
                customTheme or (1L shl 61),
            )
        )
    }

    @Test
    fun `color treatment preserves alpha and mirrors the launcher multiplier`() {
        assertEquals(0x80D66B00.toInt(), ThemeIconDarkEffect.apply(0x80FF8000.toInt()))
    }
}
