package com.fankes.coloros.notify.hook.icon

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconDarkEffectTest {

    @Test
    fun `classic theme follows ColorOS dark icon flag`() {
        val classicTheme = 2L shl 4

        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, classicTheme))
        assertTrue(
            ThemeIconDarkEffect.isEnabled(
                Configuration.UI_MODE_NIGHT_YES,
                classicTheme or (1L shl 61),
            )
        )
    }

    @Test
    fun `effect is disabled outside night mode`() {
        val enabledClassicTheme = (2L shl 4) or (1L shl 61)

        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_NO, enabledClassicTheme))
        assertFalse(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, -1L))
    }

    @Test
    fun `ColorOS dark theme is always dimmed at night`() {
        assertTrue(ThemeIconDarkEffect.isEnabled(Configuration.UI_MODE_NIGHT_YES, 3L shl 4))
    }
}
