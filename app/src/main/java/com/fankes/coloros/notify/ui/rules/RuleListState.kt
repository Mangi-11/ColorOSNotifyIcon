package com.fankes.coloros.notify.ui.rules

import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import java.util.Locale

data class RuleListState(
    val rules: List<IconRule> = emptyList(),
    val installedPackageNames: Set<String> = emptySet(),
    val installedPackagesKnown: Boolean = false,
    val query: String = "",
    val config: RuleStore.ModuleConfig = RuleStore.ModuleConfig(),
    val canEditConfig: Boolean = false,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
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

    val sections: List<RuleSection>
        get() {
            val visibleRules = filteredRules
            if (visibleRules.isEmpty()) return emptyList()
            if (!installedPackagesKnown) {
                return listOf(RuleSection(RuleSectionType.All, visibleRules))
            }
            val (installed, notInstalled) = visibleRules.partition {
                it.packageName in installedPackageNames
            }
            return buildList {
                if (installed.isNotEmpty()) add(RuleSection(RuleSectionType.Installed, installed))
                if (notInstalled.isNotEmpty()) add(RuleSection(RuleSectionType.NotInstalled, notInstalled))
            }
        }

    val installedEnabledRulePackageNames: Set<String>
        get() = rules.asSequence()
            .filter { it.isEnabled && it.packageName in installedPackageNames }
            .mapTo(linkedSetOf()) { it.packageName }

    val installedRulesEnabledAll: Boolean
        get() {
            val eligiblePackages = installedEnabledRulePackageNames
            return eligiblePackages.isNotEmpty() && rules
                .asSequence()
                .filter { it.packageName in eligiblePackages }
                .all(IconRule::isEnabledAll)
        }

}

data class RuleSection(
    val type: RuleSectionType,
    val rules: List<IconRule>,
)

enum class RuleSectionType {
    All,
    Installed,
    NotInstalled,
}
