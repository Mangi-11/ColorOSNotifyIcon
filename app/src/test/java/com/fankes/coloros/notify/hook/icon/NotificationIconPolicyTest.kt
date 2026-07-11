package com.fankes.coloros.notify.hook.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIconPolicyTest {

    @Test
    fun `Oplus push is kept only for rule library when handling is disabled`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = false),
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = true),
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(source = PolicyIconSource.DesktopTheme, handleOplusPush = false),
                isOplusPush = true,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(handleOplusPush = false),
                isOplusPush = false,
            )
        )
    }

    @Test
    fun `Oplus push preservation is independent from rule enablement`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostDefault(
                config(rulesEnabled = false, handleOplusPush = false),
                isOplusPush = true,
            )
        )
    }

    @Test
    fun `panel gate rejects disabled media and preserved push notifications`() {
        assertFalse(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(panelEnabled = false),
                isMediaNotification = false,
                isOplusPush = false,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(),
                isMediaNotification = true,
                isOplusPush = false,
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(handleOplusPush = false),
                isMediaNotification = false,
                isOplusPush = true,
            )
        )
        assertTrue(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(source = PolicyIconSource.DesktopTheme, handleOplusPush = false),
                isMediaNotification = false,
                isOplusPush = true,
            )
        )
        assertTrue(
            NotificationIconPolicy.shouldProcessPanel(
                config = config(rulesEnabled = false),
                isMediaNotification = false,
                isOplusPush = false,
            )
        )
    }

    @Test
    fun `desktop theme is resolved only when rules are enabled`() {
        assertTrue(
            NotificationIconPolicy.shouldResolveTheme(
                config(source = PolicyIconSource.DesktopTheme)
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldResolveTheme(
                config(source = PolicyIconSource.DesktopTheme, rulesEnabled = false)
            )
        )
        assertFalse(NotificationIconPolicy.shouldResolveTheme(config()))
    }

    @Test
    fun `rule selection covers enabled enabled-all and monochrome behavior`() {
        val cases = listOf(
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                monochrome = false,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = true,
                monochrome = true,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                monochrome = true,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = true,
                monochrome = false,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = false,
                monochrome = false,
                expected = RuleReplacement.None,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                NotificationIconPolicy.selectRuleReplacement(
                    config = config(),
                    ruleEnabled = case.ruleEnabled,
                    ruleEnabledAll = case.ruleEnabledAll,
                    originalIsMonochrome = case.monochrome,
                ),
            )
        }
    }

    @Test
    fun `placeholder is used only for colored originals in rule-library mode`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = false,
                originalIsMonochrome = false,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = false,
                originalIsMonochrome = true,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(
                    source = PolicyIconSource.DesktopTheme,
                    placeholderEnabled = true,
                ),
                ruleEnabled = false,
                ruleEnabledAll = false,
                originalIsMonochrome = false,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(rulesEnabled = false, placeholderEnabled = true),
                ruleEnabled = true,
                ruleEnabledAll = true,
                originalIsMonochrome = false,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(source = PolicyIconSource.DesktopTheme),
                ruleEnabled = true,
                ruleEnabledAll = true,
                originalIsMonochrome = false,
            )
        )
    }

    @Test
    fun `disabled rule may fall back to placeholder for a colored original`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = true,
                originalIsMonochrome = false,
            )
        )
    }

    @Test
    fun `original is restored only when host changed a monochrome icon to color`() {
        assertTrue(NotificationIconPolicy.shouldRestoreOriginal(true, false))
        assertFalse(NotificationIconPolicy.shouldRestoreOriginal(true, true))
        assertFalse(NotificationIconPolicy.shouldRestoreOriginal(true, null))
        assertFalse(NotificationIconPolicy.shouldRestoreOriginal(false, false))
    }

    private fun config(
        rulesEnabled: Boolean = true,
        source: PolicyIconSource = PolicyIconSource.RuleLibrary,
        panelEnabled: Boolean = true,
        handleOplusPush: Boolean = true,
        placeholderEnabled: Boolean = false,
    ) = IconPolicyConfig(
        rulesEnabled = rulesEnabled,
        source = source,
        panelEnabled = panelEnabled,
        handleOplusPush = handleOplusPush,
        placeholderEnabled = placeholderEnabled,
    )

    private data class RuleCase(
        val ruleEnabled: Boolean,
        val ruleEnabledAll: Boolean,
        val monochrome: Boolean,
        val expected: RuleReplacement,
    )
}
