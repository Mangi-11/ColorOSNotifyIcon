package com.fankes.coloros.notify.ui.rules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.ui.theme.ColorOSNotifyIconTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RuleListScreen(
    state: RuleListState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRuleEnabledChange: (IconRule, Boolean, (String) -> Unit) -> Unit,
    onRuleEnabledAllChange: (IconRule, Boolean, (String) -> Unit) -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.rules_title),
                largeTitle = stringResource(R.string.rules_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val rules = state.filteredRules
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = paddingValues,
        ) {
            item {
                SearchBar(
                    modifier = Modifier.padding(bottom = SearchBarDefaults.InsideMargin.width),
                    inputField = {
                        InputField(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            onSearch = { searchExpanded = false },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            label = stringResource(R.string.rules_search_hint),
                        )
                    },
                    onExpandedChange = { searchExpanded = it },
                    expanded = searchExpanded,
                    outsideEndAction = {
                        TextButton(
                            text = stringResource(R.string.dialog_cancel),
                            modifier = Modifier.padding(end = SearchBarDefaults.InsideMargin.width),
                            onClick = {
                                onQueryChange("")
                                searchExpanded = false
                            },
                        )
                    },
                ) {}
            }
            if (!state.isLoading) {
                item { RuleSummaryCard(state = state) }
            }
            item { SmallTitle(text = stringResource(R.string.section_rule_management)) }
            if (rules.isEmpty()) {
                item {
                    EmptyRulesCard(
                        query = state.query,
                        isLoading = state.isLoading,
                        loadFailed = state.loadFailed,
                    )
                }
            } else {
                items(
                    items = rules,
                    key = { it.packageName },
                ) { rule ->
                    RuleCard(
                        rule = rule,
                        rulesEnabled = state.config.rulesEnabled,
                        ruleLibraryMode = state.config.iconSourceMode == RuleStore.IconSourceMode.RuleLibrary,
                        canEditConfig = state.canEditConfig,
                        onEnabledChange = { onRuleEnabledChange(rule, it, ::showSnackbar) },
                        onEnabledAllChange = { onRuleEnabledAllChange(rule, it, ::showSnackbar) },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RuleSummaryCard(
    state: RuleListState,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.label_rules_count_summary),
            summary = stringResource(
                R.string.rules_count_summary,
                state.rules.size,
                state.enabledRulesCount,
                state.forceAllRulesCount,
            ),
        )
    }
}

@Composable
private fun RuleCard(
    rule: IconRule,
    rulesEnabled: Boolean,
    ruleLibraryMode: Boolean,
    canEditConfig: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEnabledAllChange: (Boolean) -> Unit,
) {
    val unavailableSummary = stringResource(R.string.label_framework_waiting_summary)
    val ruleModeRequiredSummary = stringResource(R.string.label_rule_library_mode_required_summary)
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        BasicComponent(
            startAction = { RuleIcon(rule) },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = rule.appName.ifBlank { rule.packageName },
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rule.packageName,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rule.contributorName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.label_rule_contributor, rule.contributorName),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ToggleComponent(
            title = stringResource(R.string.label_rule_enable),
            summary = if (canEditConfig) {
                if (ruleLibraryMode) {
                    stringResource(R.string.label_rule_enable_summary)
                } else {
                    ruleModeRequiredSummary
                }
            } else {
                unavailableSummary
            },
            checked = rule.isEnabled,
            enabled = canEditConfig && rulesEnabled && ruleLibraryMode,
            onCheckedChange = onEnabledChange,
        )
        ToggleComponent(
            title = stringResource(R.string.label_rule_force_all),
            summary = if (canEditConfig) {
                if (ruleLibraryMode) {
                    stringResource(R.string.label_rule_force_all_summary)
                } else {
                    ruleModeRequiredSummary
                }
            } else {
                unavailableSummary
            },
            checked = rule.isEnabledAll,
            enabled = canEditConfig && rulesEnabled && ruleLibraryMode && rule.isEnabled,
            onCheckedChange = onEnabledAllChange,
        )
    }
}

@Composable
private fun RuleIcon(rule: IconRule) {
    val tint = if (rule.iconColor != 0) {
        Color(rule.iconColor)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val imageBitmap = remember(rule.iconAsset) {
        try {
            rule.iconBitmap.asImageBitmap()
        } catch (exception: Exception) {
            AppDiagnostics.logger.report(
                level = DiagnosticLevel.Error,
                event = DiagnosticEvent.IconDecodeFailed,
                message = "Unable to decode rule icon",
                cause = exception,
                attributes = mapOf(
                    "scope" to "rule_list",
                    "package" to rule.packageName,
                ),
                occurrence = OccurrencePolicy.Once(rule.packageName),
            )
            null
        }
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        imageBitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(tint),
            )
        }
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
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        enabled = enabled,
    )
}

@Composable
private fun EmptyRulesCard(
    query: String,
    isLoading: Boolean,
    loadFailed: Boolean,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    isLoading -> stringResource(R.string.rules_loading)
                    loadFailed -> stringResource(R.string.rules_load_failed)
                    query.isBlank() -> stringResource(R.string.rules_empty)
                    else -> stringResource(R.string.rules_search_empty)
                },
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun RuleListScreenPreview() {
    ColorOSNotifyIconTheme {
        RuleListScreen(
            state = RuleListState(),
            onBack = {},
            onQueryChange = {},
            onRuleEnabledChange = { _, _, _ -> },
            onRuleEnabledAllChange = { _, _, _ -> },
        )
    }
}
