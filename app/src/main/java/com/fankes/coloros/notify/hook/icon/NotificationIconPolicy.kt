package com.fankes.coloros.notify.hook.icon

internal enum class PolicyIconSource {
    RuleLibrary,
    DesktopTheme,
}

internal data class IconPolicyConfig(
    val rulesEnabled: Boolean,
    val source: PolicyIconSource,
    val panelEnabled: Boolean,
    val handleOplusPush: Boolean,
    val placeholderEnabled: Boolean,
)

internal enum class RuleReplacement {
    Rule,
    Placeholder,
    None,
}

internal object NotificationIconPolicy {

    fun shouldKeepHostDefault(
        config: IconPolicyConfig,
        isOplusPush: Boolean,
    ): Boolean =
        config.source == PolicyIconSource.RuleLibrary &&
            !config.handleOplusPush &&
            isOplusPush

    fun shouldProcessPanel(
        config: IconPolicyConfig,
        isMediaNotification: Boolean,
        isOplusPush: Boolean,
    ): Boolean =
        config.panelEnabled &&
            !isMediaNotification &&
            !shouldKeepHostDefault(config, isOplusPush)

    fun shouldResolveTheme(config: IconPolicyConfig): Boolean =
        config.rulesEnabled && config.source == PolicyIconSource.DesktopTheme

    fun selectRuleReplacement(
        config: IconPolicyConfig,
        ruleEnabled: Boolean,
        ruleEnabledAll: Boolean,
        originalIsMonochrome: Boolean,
    ): RuleReplacement {
        if (!config.rulesEnabled || config.source != PolicyIconSource.RuleLibrary) {
            return RuleReplacement.None
        }
        if (ruleEnabled && (ruleEnabledAll || !originalIsMonochrome)) {
            return RuleReplacement.Rule
        }
        return if (config.placeholderEnabled && !originalIsMonochrome) {
            RuleReplacement.Placeholder
        } else {
            RuleReplacement.None
        }
    }

    fun shouldRestoreOriginal(
        originalIsMonochrome: Boolean,
        currentIsMonochrome: Boolean?,
    ): Boolean = originalIsMonochrome && currentIsMonochrome == false
}
