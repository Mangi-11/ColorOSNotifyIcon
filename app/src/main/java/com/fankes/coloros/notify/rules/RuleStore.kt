package com.fankes.coloros.notify.rules

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object RuleStore {

    data class ModuleConfig(
        val moduleEnabled: Boolean = true,
        val rulesEnabled: Boolean = true,
        val panelIconReplacementEnabled: Boolean = true,
        val oplusPushSpecialHandlingEnabled: Boolean = true,
        val placeholderIconEnabled: Boolean = false,
    )

    data class MirrorSnapshot(
        val rulesJson: String,
        val rulesCount: Int,
        val rulesUpdatedAt: Long,
        val configUpdatedAt: Long,
        val configValues: Map<String, Boolean>,
    )

    const val GROUP_CONFIG = "config"
    const val RULES_FILE_NAME = "rules.json"
    private const val PREFS_NAME = GROUP_CONFIG
    private val OBSOLETE_KEYS = arrayOf(
        "md3_style_enabled",
        "icon_corner_dp",
        "module_enabled",
        "icon_enhancement_enabled",
        "last_framework_name",
        "last_framework_version",
        "last_framework_api",
        "last_scope_list",
        "last_framework_connected_at",
    )
    const val KEY_RULES_JSON = "rules_json"
    const val KEY_RULES_COUNT = "rules_count"
    const val KEY_RULES_UPDATED_AT = "rules_updated_at"
    const val KEY_CONFIG_UPDATED_AT = "config_updated_at"
    const val KEY_MODULE_ENABLED = "config.module_enabled"
    const val KEY_RULES_ENABLED = "config.rules_enabled"
    const val KEY_PANEL_ICON_REPLACEMENT_ENABLED = "config.panel_icon_replacement_enabled"
    const val KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED = "config.oplus_push_special_handling_enabled"
    const val KEY_PLACEHOLDER_ICON_ENABLED = "config.placeholder_icon_enabled"
    private const val KEY_RULE_ENABLED_PREFIX = "rule.enabled."
    private const val KEY_RULE_ENABLED_ALL_PREFIX = "rule.enabled_all."

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        cleanupObsoleteKeys()
        ensureSyncMetadata()
    }

    private val prefs: SharedPreferences
        get() {
            check(::appContext.isInitialized) { "RuleStore 尚未初始化" }
            return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

    val rulesJson: String
        get() = prefs.getString(KEY_RULES_JSON, "").orEmpty()

    val rulesCount: Int
        get() = prefs.getInt(KEY_RULES_COUNT, 0)

    val rulesUpdatedAt: Long
        get() = prefs.getLong(KEY_RULES_UPDATED_AT, 0L)

    val localConfigUpdatedAt: Long
        get() = prefs.getLong(KEY_CONFIG_UPDATED_AT, 0L)

    val moduleConfig: ModuleConfig
        get() = readModuleConfig(prefs)

    val rules: List<IconRule>
        get() = applyRuleOverrides(parseRules(rulesJson), prefs)

    fun updateRules(json: String, updatedAt: Long = System.currentTimeMillis()) {
        val count = parseRules(json).size
        prefs.edit()
            .putString(KEY_RULES_JSON, json)
            .putInt(KEY_RULES_COUNT, count)
            .putLong(KEY_RULES_UPDATED_AT, updatedAt)
            .putLong(KEY_CONFIG_UPDATED_AT, updatedAt)
            .apply()
    }

    fun captureMirrorSnapshot(): MirrorSnapshot {
        val localPrefs = prefs
        return MirrorSnapshot(
            rulesJson = rulesJson,
            rulesCount = rulesCount,
            rulesUpdatedAt = rulesUpdatedAt,
            configUpdatedAt = localConfigUpdatedAt,
            configValues = localPrefs.all
                .filterKeys(::isMirroredConfigKey)
                .mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }
                .toMap(),
        )
    }

    @Suppress("ApplySharedPref")
    fun mirrorTo(remotePrefs: SharedPreferences, snapshot: MirrorSnapshot): Boolean = remotePrefs.edit().apply {
        OBSOLETE_KEYS.forEach(::remove)
        putInt(KEY_RULES_COUNT, snapshot.rulesCount)
        putLong(KEY_RULES_UPDATED_AT, snapshot.rulesUpdatedAt)
        putLong(KEY_CONFIG_UPDATED_AT, snapshot.configUpdatedAt)
        mirrorConfigValuesTo(snapshot.configValues, remotePrefs, this)
    }.commit()

    fun setRulesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_RULES_ENABLED, enabled)
            .markConfigChanged()
            .apply()
    }

    fun setPanelIconReplacementEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PANEL_ICON_REPLACEMENT_ENABLED, enabled)
            .markConfigChanged()
            .apply()
    }

    fun setOplusPushSpecialHandlingEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED, enabled)
            .markConfigChanged()
            .apply()
    }

    fun setPlaceholderIconEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PLACEHOLDER_ICON_ENABLED, enabled)
            .markConfigChanged()
            .apply()
    }

    fun setRuleEnabled(packageName: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean(ruleEnabledKey(packageName), enabled)
            .markConfigChanged()
            .apply()
    }

    fun setRuleEnabledAll(packageName: String, enabledAll: Boolean) {
        prefs.edit()
            .putBoolean(ruleEnabledAllKey(packageName), enabledAll)
            .markConfigChanged()
            .apply()
    }

    fun parseRules(json: String): List<IconRule> {
        if (json.isBlank()) return emptyList()
        return buildList {
            runCatching {
                val array = JSONArray(json)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    IconRule.fromJson(item)?.let(::add)
                }
            }
        }
    }

    fun readModuleConfig(source: SharedPreferences?): ModuleConfig {
        if (source == null) return ModuleConfig()
        return ModuleConfig(
            moduleEnabled = source.getBoolean(KEY_MODULE_ENABLED, true),
            rulesEnabled = source.getBoolean(KEY_RULES_ENABLED, true),
            panelIconReplacementEnabled = source.getBoolean(KEY_PANEL_ICON_REPLACEMENT_ENABLED, true),
            oplusPushSpecialHandlingEnabled = source.getBoolean(KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED, true),
            placeholderIconEnabled = source.getBoolean(KEY_PLACEHOLDER_ICON_ENABLED, false),
        )
    }

    fun applyRuleOverrides(
        rules: List<IconRule>,
        source: SharedPreferences?,
    ): List<IconRule> {
        if (source == null) return rules
        return rules.map { rule ->
            rule.copy(
                isEnabled = source.getBoolean(ruleEnabledKey(rule.packageName), rule.isEnabled),
                isEnabledAll = source.getBoolean(ruleEnabledAllKey(rule.packageName), rule.isEnabledAll),
            )
        }
    }

    @Suppress("ApplySharedPref")
    private fun cleanupObsoleteKeys() {
        if (OBSOLETE_KEYS.none(prefs::contains)) return
        prefs.edit().apply {
            OBSOLETE_KEYS.forEach(::remove)
        }.commit()
    }

    private fun ensureSyncMetadata() {
        if (prefs.contains(KEY_CONFIG_UPDATED_AT)) return
        val hasExistingConfig = prefs.contains(KEY_RULES_JSON) ||
            prefs.contains(KEY_RULES_COUNT) ||
            prefs.contains(KEY_RULES_UPDATED_AT)
        if (!hasExistingConfig) return
        val fallbackUpdatedAt = prefs.getLong(KEY_RULES_UPDATED_AT, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_CONFIG_UPDATED_AT, fallbackUpdatedAt)
            .apply()
    }

    private fun mirrorConfigValuesTo(
        localConfig: Map<String, Boolean>,
        remotePrefs: SharedPreferences,
        editor: SharedPreferences.Editor,
    ) {
        remotePrefs.all.keys
            .filter(::isMirroredConfigKey)
            .filterNot(localConfig::containsKey)
            .forEach(editor::remove)
        localConfig.forEach { (key, value) ->
            editor.putBoolean(key, value)
        }
    }

    private fun SharedPreferences.Editor.markConfigChanged() =
        putLong(KEY_CONFIG_UPDATED_AT, System.currentTimeMillis())

    private fun isMirroredConfigKey(key: String) =
        key == KEY_MODULE_ENABLED ||
            key == KEY_RULES_ENABLED ||
            key == KEY_PANEL_ICON_REPLACEMENT_ENABLED ||
            key == KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED ||
            key == KEY_PLACEHOLDER_ICON_ENABLED ||
            key.startsWith(KEY_RULE_ENABLED_PREFIX) ||
            key.startsWith(KEY_RULE_ENABLED_ALL_PREFIX)

    private fun ruleEnabledKey(packageName: String) = KEY_RULE_ENABLED_PREFIX + packageName

    private fun ruleEnabledAllKey(packageName: String) = KEY_RULE_ENABLED_ALL_PREFIX + packageName
}
