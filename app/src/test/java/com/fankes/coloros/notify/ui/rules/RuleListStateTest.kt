package com.fankes.coloros.notify.ui.rules

import com.fankes.coloros.notify.rules.IconAsset
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleListStateTest {

    @Test
    fun `installed rules are grouped before rules for unavailable apps`() {
        val state = RuleListState(
            rules = listOf(rule("missing.one"), rule("installed"), rule("missing.two")),
            installedPackageNames = setOf("installed"),
            installedPackagesKnown = true,
            isLoading = false,
        )

        assertEquals(
            listOf(RuleSectionType.Installed, RuleSectionType.NotInstalled),
            state.sections.map { it.type },
        )
        assertEquals(listOf("installed"), state.sections[0].rules.map { it.packageName })
        assertEquals(
            listOf("missing.one", "missing.two"),
            state.sections[1].rules.map { it.packageName },
        )
    }

    @Test
    fun `search preserves installation grouping`() {
        val state = RuleListState(
            rules = listOf(rule("installed.app", "Camera"), rule("missing.app", "Camera Remote")),
            installedPackageNames = setOf("installed.app"),
            installedPackagesKnown = true,
            query = "camera",
            isLoading = false,
        )

        assertEquals(2, state.sections.size)
        assertEquals(RuleSectionType.Installed, state.sections.first().type)
    }

    @Test
    fun `unknown installation snapshot keeps the original stable order`() {
        val state = RuleListState(
            rules = listOf(rule("first"), rule("second")),
            installedPackagesKnown = false,
            isLoading = false,
        )

        assertEquals(listOf(RuleSectionType.All), state.sections.map { it.type })
        assertEquals(listOf("first", "second"), state.sections.single().rules.map { it.packageName })
    }

    @Test
    fun `bulk replacement includes only installed enabled rules`() {
        val state = RuleListState(
            rules = listOf(
                rule("installed.enabled", enabled = true),
                rule("installed.disabled", enabled = false),
                rule("missing.enabled", enabled = true),
            ),
            installedPackageNames = setOf("installed.enabled", "installed.disabled"),
            installedPackagesKnown = true,
            isLoading = false,
        )

        assertEquals(setOf("installed.enabled"), state.installedEnabledRulePackageNames)
    }

    @Test
    fun `bulk replacement is checked only when every eligible rule forces replacement`() {
        val allEnabled = RuleListState(
            rules = listOf(
                rule("one", enabledAll = true),
                rule("two", enabledAll = true),
            ),
            installedPackageNames = setOf("one", "two"),
            installedPackagesKnown = true,
        )
        val mixed = allEnabled.copy(
            rules = listOf(
                rule("one", enabledAll = true),
                rule("two", enabledAll = false),
            )
        )

        assertEquals(true, allEnabled.installedRulesEnabledAll)
        assertEquals(false, mixed.installedRulesEnabledAll)
    }

    private fun rule(
        packageName: String,
        appName: String = packageName,
        enabled: Boolean = true,
        enabledAll: Boolean = false,
    ): IconRule = IconRule(
        definition = RuleDefinition(
            appName = appName,
            packageName = packageName,
            icon = IconAsset.fromBytesForTest(byteArrayOf(1)),
            iconColor = 0,
            contributorName = "",
            enabledByDefault = true,
            enabledAllByDefault = false,
        ),
        isEnabled = enabled,
        isEnabledAll = enabledAll,
    )
}
