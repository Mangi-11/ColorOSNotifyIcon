package com.fankes.coloros.notify.ui.home

import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.rules.RuleStore

enum class RuleSyncStage {
    Idle,
    SyncingRules,
    MirroringRemote,
}

data class FrameworkConnection(
    val name: String,
    val version: String,
    val apiVersion: Int,
    val grantedScopes: Set<String>,
)

data class HomeScreenState(
    val frameworkConnection: FrameworkConnection? = null,
    val rulesCount: Int = 0,
    val rulesUpdatedAt: Long = 0L,
    val config: RuleStore.ModuleConfig = RuleStore.ModuleConfig(),
    val isConfigMirroring: Boolean = false,
    val syncStage: RuleSyncStage = RuleSyncStage.Idle,
) {
    val isModuleActive: Boolean
        get() = frameworkConnection != null

    val grantedScopes: Set<String>
        get() = frameworkConnection?.grantedScopes.orEmpty()

    val missingScopes: Set<String>
        get() = REQUIRED_SCOPES - grantedScopes

    val isSyncing: Boolean
        get() = syncStage != RuleSyncStage.Idle

    companion object {
        private val REQUIRED_SCOPES = setOf(SystemPackages.SYSTEM_SCOPE, SystemPackages.SYSTEM_UI)
    }
}
