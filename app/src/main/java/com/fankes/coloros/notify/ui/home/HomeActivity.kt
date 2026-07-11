package com.fankes.coloros.notify.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.framework.XposedServiceBridge
import com.fankes.coloros.notify.framework.RemoteConfigCoordinator
import com.fankes.coloros.notify.framework.RemoteRuleMirror
import com.fankes.coloros.notify.framework.SystemUiRestarter
import com.fankes.coloros.notify.rules.RuleRepository
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.rules.RulesActivity
import com.fankes.coloros.notify.ui.theme.ColorOSNotifyIconTheme
import io.github.libxposed.service.XposedService

class HomeActivity : ComponentActivity() {

    private var uiState by mutableStateOf(HomeScreenState())
    private var currentService: XposedService? = null

    private val frameworkListener = object : XposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            refreshLocalState(currentService = service)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshLocalState(currentService = XposedServiceBridge.getCurrentService())

        enableEdgeToEdge()
        setContent {
            ColorOSNotifyIconTheme {
                HomeScreen(
                    state = uiState,
                    onSyncRules = ::syncRules,
                    onRestartSystemUi = ::performRestartSystemUi,
                    onOpenRules = ::openRules,
                    onRulesEnabledChange = ::setRulesEnabled,
                    onIconSourceModeChange = ::setIconSourceMode,
                    onPanelIconReplacementEnabledChange = ::setPanelIconReplacementEnabled,
                    onOplusPushSpecialHandlingEnabledChange = ::setOplusPushSpecialHandlingEnabled,
                    onPlaceholderIconEnabledChange = ::setPlaceholderIconEnabled,
                )
            }
        }
    }

    private fun openRules() {
        startActivity(Intent(this, RulesActivity::class.java))
    }

    private fun refreshLocalState(currentService: XposedService? = this.currentService) {
        val serviceSnapshot = XposedServiceBridge.snapshot(currentService)
        this.currentService = currentService.takeIf { serviceSnapshot != null }
        uiState = uiState.copy(
            frameworkConnection = serviceSnapshot?.let {
                FrameworkConnection(
                    name = it.frameworkName,
                    version = it.frameworkVersion,
                    apiVersion = it.apiVersion,
                    grantedScopes = it.scopes,
                )
            },
            rulesCount = RuleStore.rulesCount,
            rulesUpdatedAt = RuleStore.rulesUpdatedAt,
            config = RuleStore.moduleConfig,
        )
    }

    private fun setRulesEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        updateConfig(service, onShowMessage) { RuleStore.setRulesEnabled(enabled) }
    }

    private fun setIconSourceMode(mode: RuleStore.IconSourceMode, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        updateConfig(service, onShowMessage) { RuleStore.setIconSourceMode(mode) }
    }

    private fun setPanelIconReplacementEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        updateConfig(service, onShowMessage) { RuleStore.setPanelIconReplacementEnabled(enabled) }
    }

    private fun setOplusPushSpecialHandlingEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        updateConfig(service, onShowMessage) { RuleStore.setOplusPushSpecialHandlingEnabled(enabled) }
    }

    private fun setPlaceholderIconEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        updateConfig(service, onShowMessage) { RuleStore.setPlaceholderIconEnabled(enabled) }
    }

    private fun updateConfig(
        service: XposedService,
        onShowMessage: (String) -> Unit,
        mutation: () -> Unit,
    ) {
        val updated = RemoteConfigCoordinator.update(service, mutation) { result ->
            if (result is RemoteRuleMirror.PublishResult.Failed) {
                onShowMessage(
                    getString(R.string.message_settings_apply_failed, result.failure.userMessage)
                )
            }
        }
        if (updated) refreshLocalState()
    }

    private fun syncRules(onShowMessage: (String) -> Unit) {
        requireFrameworkService(onShowMessage) ?: return
        uiState = uiState.copy(syncStage = RuleSyncStage.SyncingRules)
        RuleRepository.syncRules { result ->
            result.onSuccess { syncResult ->
                refreshLocalState()
                val service = currentService
                if (service == null) {
                    uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                    onShowMessage(
                        getString(R.string.message_rules_update_service_unavailable, syncResult.count)
                    )
                    return@onSuccess
                }
                uiState = uiState.copy(syncStage = RuleSyncStage.MirroringRemote)
                RemoteConfigCoordinator.publish(service) { mirrorResult ->
                    uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                    val message = when (mirrorResult) {
                        is RemoteRuleMirror.PublishResult.Published ->
                            getString(
                                R.string.message_rules_update_success,
                                syncResult.count
                            )

                        is RemoteRuleMirror.PublishResult.Failed ->
                            getString(
                                R.string.message_rules_update_not_applied,
                                syncResult.count,
                                mirrorResult.failure.userMessage,
                            )
                    }
                    onShowMessage(message)
                }
            }.onFailure { exception ->
                uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                onShowMessage(
                    getString(
                        R.string.message_rules_update_failed,
                        (exception as? RuleRepository.SyncFailure)?.userMessage
                            ?: getString(R.string.message_unknown_error)
                    )
                )
            }
        }
    }

    private fun performRestartSystemUi(onShowMessage: (String) -> Unit) {
        val service = currentService
        if (service == null) {
            onShowMessage(getString(R.string.message_service_unavailable))
            return
        }
        RemoteConfigCoordinator.publish(service) { mirrorResult ->
            when (mirrorResult) {
                is RemoteRuleMirror.PublishResult.Published -> restartSystemUiDirectly(onShowMessage)
                is RemoteRuleMirror.PublishResult.Failed -> onShowMessage(
                    getString(R.string.message_settings_not_applied, mirrorResult.failure.userMessage)
                )
            }
        }
    }

    private fun restartSystemUiDirectly(onShowMessage: (String) -> Unit) {
        SystemUiRestarter.restartSystemUi { result ->
            result.onSuccess {
                onShowMessage(getString(R.string.message_restart_requested))
            }.onFailure {
                val userMessage = (it as? SystemUiRestarter.RestartFailure)?.userMessage
                    ?: getString(R.string.message_restart_failed_generic)
                onShowMessage(
                    getString(R.string.message_restart_failed, userMessage)
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        XposedServiceBridge.addListener(frameworkListener)
    }

    override fun onStop() {
        XposedServiceBridge.removeListener(frameworkListener)
        super.onStop()
    }

    private fun requireFrameworkService(onShowMessage: (String) -> Unit): XposedService? {
        val service = currentService
        if (service == null) {
            onShowMessage(getString(R.string.message_service_unavailable))
        }
        return service
    }

}
