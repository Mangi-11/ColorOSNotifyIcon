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

internal enum class OriginalIconCompatibility {
    Unchecked,
    Compatible,
    Incompatible,
}

internal object NotificationIconPolicy {

    fun shouldKeepHostDefault(
        config: IconPolicyConfig,
        isOplusPush: Boolean,
    ): Boolean =
        shouldKeepHostAppIconBehavior(config) && isOplusPush

    fun shouldKeepHostAppIconBehavior(config: IconPolicyConfig): Boolean =
        config.source == PolicyIconSource.RuleLibrary && !config.handleOplusPush

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
        isOplusPush: Boolean,
        originalCompatibility: OriginalIconCompatibility,
    ): RuleReplacement {
        if (!config.rulesEnabled || config.source != PolicyIconSource.RuleLibrary) {
            return RuleReplacement.None
        }
        if (isOplusPush && !config.handleOplusPush) return RuleReplacement.None

        // These choices are explicit and do not depend on whether the original Icon can be loaded.
        if (ruleEnabled && (ruleEnabledAll || isOplusPush)) {
            return RuleReplacement.Rule
        }

        return when (originalCompatibility) {
            OriginalIconCompatibility.Unchecked,
            OriginalIconCompatibility.Compatible -> RuleReplacement.None

            OriginalIconCompatibility.Incompatible -> when {
                ruleEnabled -> RuleReplacement.Rule
                config.placeholderEnabled -> RuleReplacement.Placeholder
                else -> RuleReplacement.None
            }
        }
    }
}
