package com.fankes.coloros.notify.ui.home

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fankes.coloros.notify.BuildConfig
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.theme.ColorOSNotifyIconTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SOFTWARE_VERSION_PROPERTY = "ro.build.display.id.show"

@Composable
fun HomeScreen(
    state: HomeScreenState,
    onSyncRules: ((String) -> Unit) -> Unit,
    onRestartSystemUi: ((String) -> Unit) -> Unit,
    onOpenRules: () -> Unit,
    onRulesEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onIconSourceModeChange: (RuleStore.IconSourceMode, (String) -> Unit) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean, (String) -> Unit) -> Unit,
    onLauncherIconHiddenChange: (Boolean, (String) -> Unit) -> Unit,
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
            item { StatusCard(state = state) }
            item { SmallTitle(text = stringResource(R.string.section_feature_settings)) }
            item {
                SettingsCard(
                    state = state,
                    onIconSourceModeChange = { onIconSourceModeChange(it, ::showSnackbar) },
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
                    onRestartClick = { showRestartDialog = true },
                )
            }
            item { SmallTitle(text = stringResource(R.string.section_app_settings)) }
            item {
                AppSettingsCard(
                    launcherIconHidden = state.launcherIconHidden,
                    onLauncherIconHiddenChange = {
                        onLauncherIconHiddenChange(it, ::showSnackbar)
                    },
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
private fun AppSettingsCard(
    launcherIconHidden: Boolean,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        ToggleComponent(
            title = stringResource(R.string.label_hide_launcher_icon),
            checked = launcherIconHidden,
            onCheckedChange = onLauncherIconHiddenChange,
        )
    }
}

@Composable
private fun SettingsCard(
    state: HomeScreenState,
    onIconSourceModeChange: (RuleStore.IconSourceMode) -> Unit,
    onRulesEnabledChange: (Boolean) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean) -> Unit,
) {
    val canEditConfig = state.canEditConfig
    val ruleLibraryMode = state.config.iconSourceMode == RuleStore.IconSourceMode.RuleLibrary
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        IconSourceRow(state = state, onIconSourceModeChange = onIconSourceModeChange)
        ToggleComponent(
            title = stringResource(R.string.label_icon_enhancement_enabled),
            checked = state.config.rulesEnabled,
            enabled = canEditConfig,
            onCheckedChange = onRulesEnabledChange,
        )
        ToggleComponent(
            title = stringResource(R.string.label_panel_icon_replacement_enabled),
            checked = state.config.panelIconReplacementEnabled,
            enabled = canEditConfig,
            onCheckedChange = onPanelIconReplacementEnabledChange,
        )
        if (ruleLibraryMode) {
            ToggleComponent(
                title = stringResource(R.string.label_oplus_push_special_handling_enabled),
                checked = state.config.oplusPushSpecialHandlingEnabled,
                enabled = canEditConfig && state.config.rulesEnabled,
                onCheckedChange = onOplusPushSpecialHandlingEnabledChange,
            )
            ToggleComponent(
                title = stringResource(R.string.label_placeholder_icon_enabled),
                checked = state.config.placeholderIconEnabled,
                enabled = canEditConfig && state.config.rulesEnabled,
                onCheckedChange = onPlaceholderIconEnabledChange,
            )
        }
    }
}

@Composable
private fun IconSourceRow(
    state: HomeScreenState,
    onIconSourceModeChange: (RuleStore.IconSourceMode) -> Unit,
) {
    val canEditConfig = state.canEditConfig
    val current = state.config.iconSourceMode
    val modes = RuleStore.IconSourceMode.entries
    val selectedIndex = modes.indexOf(current).coerceAtLeast(0)
    val ruleLibraryTitle = stringResource(R.string.label_icon_source_rule_library)
    val ruleLibrarySummary = stringResource(R.string.label_icon_source_rule_library_summary)
    val desktopThemeTitle = stringResource(R.string.label_icon_source_desktop_theme)
    val desktopThemeSummary = stringResource(R.string.label_icon_source_desktop_theme_summary)
    val items = listOf(
        DropdownItem(text = ruleLibraryTitle, summary = ruleLibrarySummary),
        DropdownItem(text = desktopThemeTitle, summary = desktopThemeSummary),
    )
    OverlaySpinnerPreference(
        title = stringResource(R.string.label_icon_source_mode),
        items = items,
        selectedIndex = selectedIndex,
        enabled = canEditConfig,
        onSelectedIndexChange = { index ->
            val mode = modes[index]
            if (mode != current) onIconSourceModeChange(mode)
        },
    )
}

@Composable
private fun StatusCard(state: HomeScreenState) {
    val context = LocalContext.current
    val moduleVersionText = stringResource(
        R.string.status_hero_module_version,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    )
    val softwareVersionText = remember { softwareVersionText() }
    val heroSpec = statusHeroSpec(context, state, moduleVersionText, softwareVersionText)

    StatusHeroCard(spec = heroSpec)
}

@Composable
private fun StatusHeroCard(spec: StatusHeroSpec) {
    Card(
        modifier = Modifier
            .padding(horizontal = StatusHeroDefaults.OutsidePadding)
            .padding(bottom = StatusHeroDefaults.OutsidePadding)
            .fillMaxWidth(),
        insideMargin = PaddingValues(),
        colors = CardDefaults.defaultColors(
            color = spec.containerColor,
            contentColor = spec.accentColor,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StatusHeroDefaults.MinHeight)
                .squircleClip(CardDefaults.CornerRadius),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(StatusHeroDefaults.ContentPadding)
                    .padding(end = StatusHeroDefaults.IconReserve),
            ) {
                Text(
                    text = spec.status,
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = spec.versionLine,
                    modifier = Modifier.padding(top = StatusHeroDefaults.TitleGap),
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = spec.footnote,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(StatusHeroDefaults.ContentPadding)
                    .padding(end = StatusHeroDefaults.IconReserve),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusHeroGlyph(
                spec = spec,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(
                        x = StatusHeroDefaults.IconOffset,
                        y = StatusHeroDefaults.IconOffset,
                    )
                    .graphicsLayer { alpha = StatusHeroDefaults.IconAlpha },
            )
        }
    }
}

@Composable
private fun StatusHeroGlyph(
    spec: StatusHeroSpec,
    modifier: Modifier = Modifier,
) {
    if (spec.showCheckRing) {
        Box(
            modifier = modifier
                .size(StatusHeroDefaults.IconSize)
                .border(
                    width = StatusHeroDefaults.IconStroke,
                    color = spec.accentColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                modifier = Modifier.size(StatusHeroDefaults.CheckIconSize),
                tint = spec.accentColor,
            )
        }
    } else {
        Icon(
            imageVector = spec.icon,
            contentDescription = null,
            modifier = modifier.size(StatusHeroDefaults.IconSize),
            tint = spec.accentColor,
        )
    }
}

@Composable
private fun RulesCard(
    state: HomeScreenState,
    onOpenRules: () -> Unit,
    onSyncRules: () -> Unit,
    onRestartClick: () -> Unit,
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
            summary = if (lastSyncText == null) {
                stringResource(R.string.label_rules_snapshot_never, state.rulesCount)
            } else {
                stringResource(R.string.label_rules_snapshot_synced, state.rulesCount, lastSyncText)
            },
        )
        ArrowPreference(
            title = stringResource(R.string.label_manage_rules),
            onClick = onOpenRules,
        )
        BasicComponent(
            title = when (state.syncStage) {
                RuleSyncStage.Idle -> stringResource(R.string.button_sync_rules)
                RuleSyncStage.SyncingRules -> stringResource(R.string.button_syncing_rules)
                RuleSyncStage.MirroringRemote -> stringResource(R.string.button_mirroring_rules)
            },
            endActions = if (!state.isSyncing) {
                {
                    Icon(
                        imageVector = MiuixIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            } else null,
            onClick = onSyncRules,
            enabled = !state.isSyncing && state.canEditConfig,
        )
        BasicComponent(
            title = stringResource(R.string.label_restart_systemui),
            endActions = {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            onClick = onRestartClick,
            enabled = state.canEditConfig,
        )
    }
}

@Composable
private fun ToggleComponent(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
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

@Composable
private fun statusHeroSpec(
    context: android.content.Context,
    state: HomeScreenState,
    moduleVersionText: String,
    softwareVersionText: String,
): StatusHeroSpec {
    val frameworkConnection = state.frameworkConnection
    val successColor = Color(0xFF34C759)
    val warningColor = Color(0xFFFF9500)
    return when {
        frameworkConnection == null -> {
            val accentColor = MiuixTheme.colorScheme.error
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_inactive_title),
                versionLine = moduleVersionText,
                footnote = context.getString(R.string.status_hero_inactive_detail),
                accentColor = accentColor,
                containerColor = accentColor.copy(alpha = StatusHeroDefaults.ContainerAlpha),
                icon = MiuixIcons.Info,
                showCheckRing = false,
            )
        }
        state.missingScopes.isNotEmpty() -> {
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_missing_scopes_title),
                versionLine = moduleApiText(
                    context = context,
                    moduleVersionText = moduleVersionText,
                    apiVersion = frameworkConnection.apiVersion,
                ),
                footnote = context.getString(
                    R.string.status_hero_missing_scopes_detail,
                    state.missingScopes.joinToString(),
                ),
                accentColor = warningColor,
                containerColor = warningColor.copy(alpha = StatusHeroDefaults.ContainerAlpha),
                icon = MiuixIcons.Info,
                showCheckRing = false,
            )
        }
        else -> {
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_active_title),
                versionLine = moduleApiText(
                    context = context,
                    moduleVersionText = moduleVersionText,
                    apiVersion = frameworkConnection.apiVersion,
                ),
                footnote = softwareVersionText,
                accentColor = successColor,
                containerColor = successColor.copy(alpha = StatusHeroDefaults.ContainerAlpha),
                icon = MiuixIcons.Basic.Check,
                showCheckRing = true,
            )
        }
    }
}

private fun moduleApiText(
    context: android.content.Context,
    moduleVersionText: String,
    apiVersion: Int,
): String = context.getString(
    R.string.status_hero_module_api,
    moduleVersionText,
    apiVersion,
)

private data class StatusHeroSpec(
    val status: String,
    val versionLine: String,
    val footnote: String,
    val accentColor: Color,
    val containerColor: Color,
    val icon: ImageVector,
    val showCheckRing: Boolean,
)

private fun softwareVersionText(): String = cachedSoftwareVersion

private val cachedSoftwareVersion: String by lazy(::readSoftwareVersion)

@SuppressLint("PrivateApi") // ColorOS-only display property; Build.DISPLAY is the safe fallback.
private fun readSoftwareVersion(): String = try {
    (Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, SOFTWARE_VERSION_PROPERTY) as? String)
        ?.trim()
        .orEmpty()
        .ifBlank { Build.DISPLAY.orEmpty() }
} catch (exception: Exception) {
    AppDiagnostics.logger.report(
        level = DiagnosticLevel.Warning,
        event = DiagnosticEvent.SystemInfoReadFailed,
        message = "Unable to read the ColorOS software version property",
        cause = exception,
        attributes = mapOf("scope" to "software_version"),
        occurrence = OccurrencePolicy.Once("software_version"),
    )
    Build.DISPLAY.orEmpty()
}

private object StatusHeroDefaults {
    val OutsidePadding = 12.dp
    val ContentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)
    val MinHeight = 144.dp
    val IconReserve = 116.dp
    val IconSize = 116.dp
    val CheckIconSize = 84.dp
    val IconOffset = 24.dp
    val IconStroke = 4.dp
    val TitleGap = 6.dp
    const val ContainerAlpha = 0.16f
    const val IconAlpha = 0.72f
}

@Preview(
    name = "Home Screen Light",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun HomeScreenLightPreview() {
    ColorOSNotifyIconTheme(darkTheme = false) {
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
            onIconSourceModeChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
            onLauncherIconHiddenChange = { _, _ -> },
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
    ColorOSNotifyIconTheme(darkTheme = true) {
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
            onIconSourceModeChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
            onLauncherIconHiddenChange = { _, _ -> },
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
    ColorOSNotifyIconTheme(darkTheme = false) {
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
            onIconSourceModeChange = { _, _ -> },
            onPanelIconReplacementEnabledChange = { _, _ -> },
            onOplusPushSpecialHandlingEnabledChange = { _, _ -> },
            onPlaceholderIconEnabledChange = { _, _ -> },
            onLauncherIconHiddenChange = { _, _ -> },
        )
    }
}
