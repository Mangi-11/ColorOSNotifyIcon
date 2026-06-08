package com.fankes.coloros.notify.ui.rules

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
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.theme.OStatusMiuixTheme
import io.github.libxposed.service.XposedService

class RulesActivity : ComponentActivity() {

    private var uiState by mutableStateOf(RuleListState())
    private var currentService: XposedService? = null

    private val frameworkListener = object : LsposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            runOnUiThread {
                refreshState(currentService = service)
                mirrorPendingRemoteStoreIfNeeded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState(currentService = LsposedServiceBridge.getCurrentService())

        enableEdgeToEdge()
        setContent {
            OStatusMiuixTheme {
                RuleListScreen(
                    state = uiState,
                    onBack = ::finish,
                    onQueryChange = ::updateQuery,
                    onRuleEnabledChange = ::setRuleEnabled,
                    onRuleEnabledAllChange = ::setRuleEnabledAll,
                )
            }
        }
    }

    private fun refreshState(currentService: XposedService? = this.currentService) {
        this.currentService = currentService
        uiState = uiState.copy(
            rules = RuleStore.rules,
            config = RuleStore.moduleConfig,
            isFrameworkConnected = currentService != null,
        )
    }

    private fun updateQuery(query: String) {
        uiState = uiState.copy(query = query)
    }

    private fun setRuleEnabled(rule: IconRule, enabled: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setRuleEnabled(rule.packageName, enabled)
        onConfigChanged(onShowMessage)
    }

    private fun setRuleEnabledAll(rule: IconRule, enabledAll: Boolean, onShowMessage: (String) -> Unit) {
        RuleStore.setRuleEnabledAll(rule.packageName, enabledAll)
        onConfigChanged(onShowMessage)
    }

    private fun onConfigChanged(onShowMessage: (String) -> Unit) {
        refreshState()
        val service = currentService
        if (service == null) {
            onShowMessage(getString(R.string.message_config_saved_local_pending))
            return
        }
        uiState = uiState.copy(isMirroring = true)
        RemoteRuleMirror.syncAsync(service) { result ->
            uiState = uiState.copy(isMirroring = false)
            refreshState()
            result.onSuccess {
                SystemUiRefreshSignal.request(this)
            }
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

    private fun mirrorPendingRemoteStoreIfNeeded() {
        val service = currentService ?: return
        if (!RuleStore.hasPendingRemoteSync) return
        uiState = uiState.copy(isMirroring = true)
        RemoteRuleMirror.syncAsync(service) { result ->
            uiState = uiState.copy(isMirroring = false)
            refreshState()
            result.onSuccess {
                SystemUiRefreshSignal.request(this)
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
}
