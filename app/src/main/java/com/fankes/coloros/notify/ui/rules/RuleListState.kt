package com.fankes.coloros.notify.ui.rules

import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import java.util.Locale

data class RuleListState(
    val rules: List<IconRule> = emptyList(),
    val query: String = "",
    val config: RuleStore.ModuleConfig = RuleStore.ModuleConfig(),
) {
    val filteredRules: List<IconRule>
        get() {
            val keyword = query.trim().lowercase(Locale.getDefault())
            if (keyword.isBlank()) return rules
            return rules.filter {
                it.appName.lowercase(Locale.getDefault()).contains(keyword) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(keyword)
            }
        }

    val enabledRulesCount: Int
        get() = rules.count { it.isEnabled }

    val forceAllRulesCount: Int
        get() = rules.count { it.isEnabled && it.isEnabledAll }
}
