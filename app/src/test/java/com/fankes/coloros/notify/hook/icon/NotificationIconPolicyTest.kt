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
    fun `ColorOS app icon predicate is preserved only by the matching rule-library setting`() {
        assertTrue(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(handleOplusPush = false)
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(handleOplusPush = true)
            )
        )
        assertFalse(
            NotificationIconPolicy.shouldKeepHostAppIconBehavior(
                config(source = PolicyIconSource.DesktopTheme, handleOplusPush = false)
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
    fun `rule selection covers enabled enabled-all and mask compatibility`() {
        val cases = listOf(
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                maskCompatible = false,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = true,
                maskCompatible = true,
                expected = RuleReplacement.Rule,
            ),
            RuleCase(
                ruleEnabled = true,
                ruleEnabledAll = false,
                maskCompatible = true,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = true,
                maskCompatible = false,
                expected = RuleReplacement.None,
            ),
            RuleCase(
                ruleEnabled = false,
                ruleEnabledAll = false,
                maskCompatible = false,
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
                    isOplusPush = false,
                    originalCompatibility = case.maskCompatible.toCompatibility(),
                ),
            )
        }
    }

    @Test
    fun `forced rules are selected before the original icon is loaded`() {
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(),
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Unchecked,
            )
        )
    }

    @Test
    fun `handled Oplus push prefers enabled rule over a compatible injected bitmap`() {
        assertEquals(
            RuleReplacement.Rule,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = true),
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(handleOplusPush = false),
                ruleEnabled = true,
                ruleEnabledAll = false,
                isOplusPush = true,
                originalCompatibility = OriginalIconCompatibility.Compatible,
            )
        )
    }

    @Test
    fun `placeholder is used only for incompatible originals in rule-library mode`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = false,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Compatible,
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
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(rulesEnabled = false, placeholderEnabled = true),
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
        assertEquals(
            RuleReplacement.None,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(source = PolicyIconSource.DesktopTheme),
                ruleEnabled = true,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
    }

    @Test
    fun `disabled rule may fall back to placeholder for an incompatible original`() {
        assertEquals(
            RuleReplacement.Placeholder,
            NotificationIconPolicy.selectRuleReplacement(
                config = config(placeholderEnabled = true),
                ruleEnabled = false,
                ruleEnabledAll = true,
                isOplusPush = false,
                originalCompatibility = OriginalIconCompatibility.Incompatible,
            )
        )
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
        val maskCompatible: Boolean,
        val expected: RuleReplacement,
    )

    private fun Boolean.toCompatibility(): OriginalIconCompatibility =
        if (this) {
            OriginalIconCompatibility.Compatible
        } else {
            OriginalIconCompatibility.Incompatible
        }
}
