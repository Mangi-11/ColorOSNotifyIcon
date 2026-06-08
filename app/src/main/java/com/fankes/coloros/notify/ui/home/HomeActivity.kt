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
import com.fankes.coloros.notify.framework.LsposedServiceBridge
import com.fankes.coloros.notify.framework.RemoteRuleMirror
import com.fankes.coloros.notify.framework.SystemUiRefreshSignal
import com.fankes.coloros.notify.framework.SystemUiRestarter
import com.fankes.coloros.notify.rules.RuleRepository
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.rules.RulesActivity
import com.fankes.coloros.notify.ui.theme.ColorOSNotifyIconTheme
import io.github.libxposed.service.XposedService

class HomeActivity : ComponentActivity() {

    private var uiState by mutableStateOf(HomeScreenState())
    private var currentService: XposedService? = null

    private val frameworkListener = object : LsposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            runOnUiThread {
                refreshLocalState(currentService = service)
                mirrorPendingRemoteStoreIfNeeded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshLocalState(currentService = LsposedServiceBridge.getCurrentService())

        enableEdgeToEdge()
        setContent {
            ColorOSNotifyIconTheme {
                HomeScreen(
                    state = uiState,
                    onSyncRules = ::syncRules,
                    onRestartSystemUi = ::performRestartSystemUi,
                    onOpenRules = ::openRules,
                    onRulesEnabledChange = ::setRulesEnabled,
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
        this.currentService = currentService
        uiState = uiState.copy(
            frameworkConnection = currentService?.toFrameworkConnection(),
            rulesCount = RuleStore.rulesCount,
            rulesUpdatedAt = RuleStore.rulesUpdatedAt,
            config = RuleStore.moduleConfig,
        )
    }

    private fun setRulesEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setRulesEnabled(enabled)
        onConfigChanged(onShowMessage)
    }

    private fun setPanelIconReplacementEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setPanelIconReplacementEnabled(enabled)
        onConfigChanged(onShowMessage)
    }

    private fun setOplusPushSpecialHandlingEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setOplusPushSpecialHandlingEnabled(enabled)
        onConfigChanged(onShowMessage)
    }

    private fun setPlaceholderIconEnabled(enabled: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setPlaceholderIconEnabled(enabled)
        onConfigChanged(onShowMessage)
    }

    private fun onConfigChanged(onShowMessage: (String) -> Unit) {
        refreshLocalState()
        val service = currentService
        if (service == null) {
            onShowMessage(getString(R.string.message_config_saved_local_pending))
            return
        }
        mirrorToRemoteStore(service) { result ->
            result.onFailure {
                onShowMessage(
                    getString(
                        R.string.message_config_mirror_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private fun syncRules(onShowMessage: (String) -> Unit) {
        uiState = uiState.copy(syncStage = RuleSyncStage.SyncingRules)
        RuleRepository.syncRules { result ->
            result.onSuccess { syncResult ->
                refreshLocalState()
                val service = currentService
                if (service == null) {
                    uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                    onShowMessage(
                        getString(
                            R.string.message_rules_sync_success_local_pending,
                            syncResult.count
                        )
                    )
                    return@onSuccess
                }

                uiState = uiState.copy(syncStage = RuleSyncStage.MirroringRemote)
                mirrorToRemoteStore(service) { mirrorResult ->
                    uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                    val message = mirrorResult.fold(
                        onSuccess = {
                            getString(
                                R.string.message_rules_sync_success_remote,
                                syncResult.count
                            )
                        },
                        onFailure = {
                            getString(
                                R.string.message_rules_sync_mirror_failed,
                                syncResult.count,
                                it.message ?: it.javaClass.simpleName
                            )
                        },
                    )
                    onShowMessage(message)
                }
            }.onFailure {
                uiState = uiState.copy(syncStage = RuleSyncStage.Idle)
                onShowMessage(
                    getString(
                        R.string.message_rules_sync_failed,
                        it.message ?: it.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun mirrorPendingRemoteStoreIfNeeded() {
        val service = currentService ?: return
        if (!RuleStore.hasPendingRemoteSync) return
        mirrorToRemoteStore(service)
    }

    private fun mirrorToRemoteStore(
        service: XposedService,
        requestRefresh: Boolean = true,
        onResult: ((Result<RemoteRuleMirror.SyncResult>) -> Unit)? = null,
    ) {
        RemoteRuleMirror.syncAsync(service) { result ->
            if (requestRefresh) {
                result.onSuccess {
                    SystemUiRefreshSignal.request(this)
                }
            }
            onResult?.invoke(result)
        }
    }

    private fun performRestartSystemUi(onShowMessage: (String) -> Unit) {
        val service = currentService
        if (service == null && RuleStore.hasPendingRemoteSync) {
            onShowMessage(getString(R.string.message_pending_sync_then_restart))
            return
        }
        if (service == null) {
            restartSystemUiDirectly(onShowMessage)
            return
        }
        mirrorToRemoteStore(service, requestRefresh = false) { mirrorResult ->
            mirrorResult.onSuccess {
                restartSystemUiDirectly(onShowMessage)
            }.onFailure {
                onShowMessage(
                    getString(
                        R.string.message_config_not_synced,
                        it.message ?: it.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun restartSystemUiDirectly(onShowMessage: (String) -> Unit) {
        SystemUiRestarter.restartSystemUi { result ->
            result.onSuccess {
                onShowMessage(getString(R.string.message_restart_requested))
            }.onFailure {
                onShowMessage(
                    getString(
                        R.string.message_restart_failed,
                        it.message ?: it.javaClass.simpleName
                    )
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LsposedServiceBridge.addListener(frameworkListener)
    }

    override fun onStop() {
        LsposedServiceBridge.removeListener(frameworkListener)
        super.onStop()
    }

    private fun XposedService.toFrameworkConnection() = FrameworkConnection(
        name = frameworkName,
        version = frameworkVersion,
        apiVersion = apiVersion,
        grantedScopes = scope.toSet(),
    )
}
