package com.fankes.coloros.notify.rules

internal data class LocalRuleMigration(
    val payloadToMigrate: String?,
    val removeLegacyKey: Boolean,
) {
    companion object {
        fun plan(values: Map<String, *>): LocalRuleMigration {
            if (!values.containsKey(RuleStore.KEY_RULES_JSON)) {
                return LocalRuleMigration(payloadToMigrate = null, removeLegacyKey = false)
            }
            val hasPublishedPointer = (values[RuleStore.KEY_RULES_FILE_NAME] as? String)
                ?.isNotBlank() == true
            return LocalRuleMigration(
                payloadToMigrate = (values[RuleStore.KEY_RULES_JSON] as? String)
                    ?.takeUnless { hasPublishedPointer },
                removeLegacyKey = true,
            )
        }
    }
}
