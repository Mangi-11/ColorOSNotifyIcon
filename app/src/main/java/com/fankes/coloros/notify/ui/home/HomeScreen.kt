package com.fankes.coloros.notify.ui.home

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Refresh
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fankes.coloros.notify.BuildConfig
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.ui.theme.OStatusMiuixTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon

import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: HomeScreenState,
    onSyncRules: ((String) -> Unit) -> Unit,
    onRestartSystemUi: ((String) -> Unit) -> Unit,
    onOpenRules: () -> Unit,
    onRulesEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean, (String) -> Unit) -> Unit,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                largeTitle = stringResource(R.string.home_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = paddingValues,
        ) {
            item { SmallTitle(text = stringResource(R.string.section_module_status)) }
            item { StatusCard(state = state, onRestartClick = { showRestartDialog = true }) }
            item { SmallTitle(text = stringResource(R.string.section_feature_settings)) }
            item {
                SettingsCard(
                    state = state,
                    onRulesEnabledChange = { onRulesEnabledChange(it, ::showSnackbar) },
                    onPanelIconReplacementEnabledChange = {
                        onPanelIconReplacementEnabledChange(it, ::showSnackbar)
                    },
                    onOplusPushSpecialHandlingEnabledChange = {
                        onOplusPushSpecialHandlingEnabledChange(it, ::showSnackbar)
                    },
                    onPlaceholderIconEnabledChange = {
                        onPlaceholderIconEnabledChange(it, ::showSnackbar)
                    },
                )
            }
            item { SmallTitle(text = stringResource(R.string.section_rules_data)) }
            item {
                RulesCard(
                    state = state,
                    onOpenRules = onOpenRules,
                    onSyncRules = { onSyncRules(::showSnackbar) },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        RestartDialog(
            show = showRestartDialog,
            onDismiss = { showRestartDialog = false },
            onConfirm = {
                showRestartDialog = false
                onRestartSystemUi(::showSnackbar)
            },
        )
    }
}

@Composable
private fun SettingsCard(
    state: HomeScreenState,
    onRulesEnabledChange: (Boolean) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        ToggleComponent(
            title = stringResource(R.string.label_rules_enabled),
            summary = if (state.isConfigMirroring) {
                stringResource(R.string.label_config_mirroring)
            } else if (state.isModuleActive) {
                stringResource(R.string.label_rules_enabled_summary)
            } else {
                stringResource(R.string.label_framework_waiting_summary)
            },
            checked = state.config.rulesEnabled,
            enabled = !state.isConfigMirroring,
            onCheckedChange = onRulesEnabledChange,
        )
        ToggleComponent(
            title = stringResource(R.string.label_panel_icon_replacement_enabled),
            summary = stringResource(R.string.label_panel_icon_replacement_enabled_summary),
            checked = state.config.panelIconReplacementEnabled,
            enabled = !state.isConfigMirroring,
            onCheckedChange = onPanelIconReplacementEnabledChange,
        )
        ToggleComponent(
            title = stringResource(R.string.label_oplus_push_special_handling_enabled),
            summary = stringResource(R.string.label_oplus_push_special_handling_enabled_summary),
            checked = state.config.oplusPushSpecialHandlingEnabled,
            enabled = !state.isConfigMirroring && state.config.rulesEnabled,
            onCheckedChange = onOplusPushSpecialHandlingEnabledChange,
        )
        ToggleComponent(
            title = stringResource(R.string.label_placeholder_icon_enabled),
            summary = stringResource(R.string.label_placeholder_icon_enabled_summary),
            checked = state.config.placeholderIconEnabled,
            enabled = !state.isConfigMirroring && state.config.rulesEnabled,
            onCheckedChange = onPlaceholderIconEnabledChange,
        )
    }
}

@Composable
private fun StatusCard(
    state: HomeScreenState,
    onRestartClick: () -> Unit,
) {
    val context = LocalContext.current
    val indicatorColor = when {
        !state.isModuleActive -> MiuixTheme.colorScheme.error
        state.missingScopes.isNotEmpty() -> Color(0xFFFF9500)
        else -> Color(0xFF34C759)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.label_module),
            summary = moduleStatusText(context, state),
            startAction = {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(indicatorColor),
                )
            },
        )
        BasicComponent(
            title = stringResource(R.string.label_framework),
            summary = frameworkStatusText(context, state),
        )
        BasicComponent(
            title = stringResource(R.string.label_scope),
            summary = scopeStatusText(context, state),
        )
        BasicComponent(
            title = stringResource(R.string.label_version),
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        BasicComponent(
            title = stringResource(R.string.label_restart_systemui),
            summary = stringResource(R.string.label_restart_summary),
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            onClick = onRestartClick,
        )
    }
}

@Composable
private fun RulesCard(
    state: HomeScreenState,
    onOpenRules: () -> Unit,
    onSyncRules: () -> Unit,
) {
    val lastSyncText = remember(state.rulesUpdatedAt) {
        if (state.rulesUpdatedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(state.rulesUpdatedAt))
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.label_local_rules_short),
            summary = stringResource(R.string.label_rules_count, state.rulesCount),
        )
        BasicComponent(
            title = stringResource(R.string.label_last_sync_short),
            summary = lastSyncText ?: stringResource(R.string.label_never_synced),
        )
        BasicComponent(
            title = stringResource(R.string.label_manage_rules),
            summary = stringResource(R.string.label_manage_rules_summary),
            endActions = {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            onClick = onOpenRules,
        )
        BasicComponent(
            title = stringResource(R.string.button_sync_rules),
            summary = when (state.syncStage) {
                RuleSyncStage.Idle -> stringResource(R.string.label_sync_summary)
                RuleSyncStage.SyncingRules -> stringResource(R.string.button_syncing_rules)
                RuleSyncStage.MirroringRemote -> stringResource(R.string.button_mirroring_rules)
            },
            endActions = if (!state.isSyncing) {
                {
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            } else null,
            onClick = onSyncRules,
            enabled = !state.isSyncing,
        )
    }
}

@Composable
private fun ToggleComponent(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        onClick = { if (enabled) onCheckedChange(!checked) },
        role = Role.Switch,
        enabled = enabled,
    )
}

@Composable
private fun RestartDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.dialog_restart_title),
        summary = stringResource(R.string.dialog_restart_message),
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.dialog_confirm_restart),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun moduleStatusText(
    context: android.content.Context,
    state: HomeScreenState,
): String {
    val missingScopes = state.missingScopes.joinToString()
    return when {
        !state.isModuleActive -> context.getString(R.string.status_module_not_connected)
        state.missingScopes.isNotEmpty() ->
            context.getString(R.string.status_module_connected_missing_scopes, missingScopes)
        else -> context.getString(R.string.status_module_connected_ready)
    }
}

private fun frameworkStatusText(
    context: android.content.Context,
    state: HomeScreenState,
): String {
    val frameworkConnection = state.frameworkConnection
    return if (frameworkConnection == null) {
        context.getString(R.string.status_framework_not_connected)
    } else {
        context.getString(
            R.string.status_framework_connected,
            frameworkConnection.name,
            frameworkConnection.version,
            frameworkConnection.apiVersion,
        )
    }
}

private fun scopeStatusText(
    context: android.content.Context,
    state: HomeScreenState,
): String {
    return if (state.frameworkConnection == null) {
        context.getString(R.string.status_scope_unavailable)
    } else {
        val scopeLabel = if (state.grantedScopes.isEmpty()) {
            context.getString(R.string.label_none)
        } else {
            state.grantedScopes.joinToString()
        }
        context.getString(R.string.status_scope_granted, scopeLabel)
    }
}

private fun syncButtonTextRes(syncStage: RuleSyncStage): Int = when (syncStage) {
    RuleSyncStage.Idle -> R.string.button_sync_rules
    RuleSyncStage.SyncingRules -> R.string.button_syncing_rules
    RuleSyncStage.MirroringRemote -> R.string.button_mirroring_rules
}

@Preview(
    name = "Home Screen Light",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun HomeScreenLightPreview() {
    OStatusMiuixTheme(darkTheme = false) {
        HomeScreen(
            state = HomeScreenState(
                rulesCount = 128,
                rulesUpdatedAt = 1742861100000L,
                syncStage = RuleSyncStage.Idle,
            ),
            onSyncRules = {},
            onRestartSystemUi = {},
            onOpenRules = {},
            onRulesEnabledChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
        )
    }
}

@Preview(
    name = "Home Screen Dark",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenDarkPreview() {
    OStatusMiuixTheme(darkTheme = true) {
        HomeScreen(
            state = HomeScreenState(
                rulesCount = 256,
                rulesUpdatedAt = 1742861400000L,
                syncStage = RuleSyncStage.MirroringRemote,
            ),
            onSyncRules = {},
            onRestartSystemUi = {},
            onOpenRules = {},
            onRulesEnabledChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
        )
    }
}

@Preview(
    name = "Home Screen Empty",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun HomeScreenEmptyPreview() {
    OStatusMiuixTheme(darkTheme = false) {
        HomeScreen(
            state = HomeScreenState(
                rulesCount = 0,
                rulesUpdatedAt = 0L,
                syncStage = RuleSyncStage.Idle,
            ),
            onSyncRules = {},
            onRestartSystemUi = {},
            onOpenRules = {},
            onRulesEnabledChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
        )
    }
}
