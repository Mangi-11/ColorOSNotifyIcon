package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuleMigrationTest {

    @Test
    fun migratesLegacyPayloadWhenNoPointerExists() {
        val plan = LocalRuleMigration.plan(mapOf(RuleStore.KEY_RULES_JSON to "legacy"))

        assertEquals("legacy", plan.payloadToMigrate)
        assertTrue(plan.removeLegacyKey)
    }

    @Test
    fun removesStaleLegacyPayloadWhenPointerAlreadyExists() {
        val plan = LocalRuleMigration.plan(
            mapOf(
                RuleStore.KEY_RULES_JSON to "legacy",
                RuleStore.KEY_RULES_FILE_NAME to "rules-${"a".repeat(64)}.json",
            )
        )

        assertNull(plan.payloadToMigrate)
        assertTrue(plan.removeLegacyKey)
    }

    @Test
    fun leavesModernPreferencesUntouched() {
        val plan = LocalRuleMigration.plan(emptyMap<String, Any>())

        assertNull(plan.payloadToMigrate)
        assertFalse(plan.removeLegacyKey)
    }
}
