package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MirrorValuesTest {

    @Test
    fun buildsCompleteRemoteStateWithoutLegacyKeys() {
        val snapshot = RuleStore.MirrorSnapshot(
            rulesJson = "[]",
            rulesCount = 7,
            rulesUpdatedAt = 42L,
            revision = 9L,
            contentSha256 = "a".repeat(64),
            configValues = mapOf(
                RuleStore.KEY_RULES_ENABLED to false,
                RuleStore.KEY_ICON_SOURCE_MODE to RuleStore.IconSourceMode.DesktopTheme.prefValue,
            ),
        )

        val values = RuleStore.mirrorValues(
            snapshot = snapshot,
            fileName = "rules-${"a".repeat(64)}.json",
            previousFile = "rules-${"b".repeat(64)}.json",
        )

        assertEquals(9L, values[RuleStore.KEY_CONFIG_REVISION])
        assertEquals(7, values[RuleStore.KEY_RULES_COUNT])
        assertEquals(false, values[RuleStore.KEY_RULES_ENABLED])
        assertEquals("rules-${"b".repeat(64)}.json", values[RuleStore.KEY_PREVIOUS_RULES_FILE_NAME])
        assertFalse(values.containsKey(RuleStore.KEY_CONFIG_UPDATED_AT))
    }
}
