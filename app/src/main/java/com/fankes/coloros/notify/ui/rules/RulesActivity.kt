package com.fankes.coloros.notify.ui.rules

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.framework.MainThreadCallbacks
import com.fankes.coloros.notify.framework.XposedServiceBridge
import com.fankes.coloros.notify.framework.RemoteConfigCoordinator
import com.fankes.coloros.notify.framework.RemoteRuleMirror
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.theme.ColorOSNotifyIconTheme
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class RulesActivity : ComponentActivity() {

    private var uiState by mutableStateOf(RuleListState())
    private var currentService: XposedService? = null
    private val loadRequest = AtomicLong()
    private val ruleLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rule-list-loader")
    }

    private val frameworkListener = object : XposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            refreshFrameworkState(currentService = service)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshFrameworkState(currentService = XposedServiceBridge.getCurrentService())
        loadRules()

        enableEdgeToEdge()
        setContent {
            ColorOSNotifyIconTheme {
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

    private fun refreshFrameworkState(currentService: XposedService? = this.currentService) {
        val serviceSnapshot = XposedServiceBridge.snapshot(currentService)
        this.currentService = currentService.takeIf { serviceSnapshot != null }
        uiState = uiState.copy(
            config = RuleStore.moduleConfig,
            canEditConfig = serviceSnapshot?.scopes?.containsAll(REQUIRED_SCOPES) == true,
        )
    }

    private fun loadRules() {
        val request = loadRequest.incrementAndGet()
        try {
            ruleLoader.execute {
                val rules = try {
                    RuleStore.rules
                } catch (exception: Exception) {
                    reportRuleLoadFailure(exception)
                    MainThreadCallbacks.dispatch("rule_list_load") {
                        if (request == loadRequest.get() && !isDestroyed) {
                            uiState = uiState.copy(isLoading = false, loadFailed = true)
                        }
                    }
                    return@execute
                }
                MainThreadCallbacks.dispatch("rule_list_load") {
                    if (request == loadRequest.get() && !isDestroyed) {
                        uiState = uiState.copy(
                            rules = rules,
                            config = RuleStore.moduleConfig,
                            isLoading = false,
                            loadFailed = false,
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            reportRuleLoadFailure(exception)
            MainThreadCallbacks.dispatch("rule_list_load") {
                if (request == loadRequest.get() && !isDestroyed) {
                    uiState = uiState.copy(isLoading = false, loadFailed = true)
                }
            }
        }
    }

    private fun reportRuleLoadFailure(exception: Exception) {
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.RulesLoadFailed,
            message = "Unable to load rules for the management screen",
            cause = exception,
            attributes = mapOf("phase" to "rule_list"),
            occurrence = OccurrencePolicy.Once("rule-list"),
        )
    }

    private fun updateQuery(query: String) {
        uiState = uiState.copy(query = query)
    }

    private fun setRuleEnabled(rule: IconRule, enabled: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        val updated = RemoteConfigCoordinator.update(
            service = service,
            mutation = { RuleStore.setRuleEnabled(rule.packageName, enabled) },
        ) { result ->
            showPublishFailure(result, onShowMessage)
        }
        if (!updated) return
        uiState = uiState.copy(
            rules = uiState.rules.mapRule(rule.packageName) { it.copy(isEnabled = enabled) },
            config = RuleStore.moduleConfig,
        )
    }

    private fun setRuleEnabledAll(rule: IconRule, enabledAll: Boolean, onShowMessage: (String) -> Unit) {
        val service = requireFrameworkService(onShowMessage) ?: return
        val updated = RemoteConfigCoordinator.update(
            service = service,
            mutation = { RuleStore.setRuleEnabledAll(rule.packageName, enabledAll) },
        ) { result ->
            showPublishFailure(result, onShowMessage)
        }
        if (!updated) return
        uiState = uiState.copy(
            rules = uiState.rules.mapRule(rule.packageName) { it.copy(isEnabledAll = enabledAll) },
            config = RuleStore.moduleConfig,
        )
    }

    private fun showPublishFailure(
        result: RemoteRuleMirror.PublishResult,
        onShowMessage: (String) -> Unit,
    ) {
        if (result is RemoteRuleMirror.PublishResult.Failed) {
            onShowMessage(
                getString(
                    R.string.message_config_mirror_failed,
                    result.failure.userMessage,
                )
            )
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

    override fun onDestroy() {
        loadRequest.incrementAndGet()
        ruleLoader.shutdownNow()
        super.onDestroy()
    }

    private fun requireFrameworkService(onShowMessage: (String) -> Unit): XposedService? {
        val service = currentService
        if (service == null) {
            onShowMessage(getString(R.string.message_framework_unavailable))
        }
        return service
    }

    private fun List<IconRule>.mapRule(
        packageName: String,
        transform: (IconRule) -> IconRule,
    ) = map { rule ->
        if (rule.packageName == packageName) transform(rule) else rule
    }

    companion object {
        private val REQUIRED_SCOPES = setOf(SystemPackages.SYSTEM_SCOPE, SystemPackages.SYSTEM_UI)
    }
}
