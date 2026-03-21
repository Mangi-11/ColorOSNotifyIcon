package com.fankes.coloros.notify.data

import android.content.Context
import android.content.SharedPreferences
import com.fankes.coloros.notify.bean.IconDataBean
import org.json.JSONArray

object ConfigData {

    const val GROUP_CONFIG = "config"
    const val RULES_FILE_NAME = "rules.json"
    private const val PREFS_NAME = GROUP_CONFIG

    const val KEY_MODULE_ENABLED = "module_enabled"
    const val KEY_ICON_ENHANCEMENT_ENABLED = "icon_enhancement_enabled"
    const val KEY_MD3_STYLE_ENABLED = "md3_style_enabled"
    const val KEY_ICON_CORNER_DP = "icon_corner_dp"
    const val KEY_RULES_JSON = "rules_json"
    const val KEY_RULES_COUNT = "rules_count"
    const val KEY_RULES_UPDATED_AT = "rules_updated_at"
    const val KEY_LAST_FRAMEWORK_NAME = "last_framework_name"
    const val KEY_LAST_FRAMEWORK_VERSION = "last_framework_version"
    const val KEY_LAST_FRAMEWORK_API = "last_framework_api"
    const val KEY_LAST_SCOPE_LIST = "last_scope_list"
    const val KEY_LAST_FRAMEWORK_CONNECTED_AT = "last_framework_connected_at"

    private lateinit var appContext: Context

    data class HookSnapshot(
        val moduleEnabled: Boolean = true,
        val iconEnhancementEnabled: Boolean = true,
        val md3StyleEnabled: Boolean = true,
        val iconCornerDp: Int = 15,
    )

    data class FrameworkSnapshot(
        val frameworkName: String = "",
        val frameworkVersion: String = "",
        val apiVersion: Int = 0,
        val scopes: List<String> = emptyList(),
        val lastConnectedAt: Long = 0L,
    ) {
        val hasConnectionRecord get() = lastConnectedAt > 0L
    }

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private val prefs: SharedPreferences
        get() {
            check(::appContext.isInitialized) { "ConfigData 尚未初始化" }
            return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

    var isModuleEnabled: Boolean
        get() = prefs.getBoolean(KEY_MODULE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MODULE_ENABLED, value).apply()

    var isIconEnhancementEnabled: Boolean
        get() = prefs.getBoolean(KEY_ICON_ENHANCEMENT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ICON_ENHANCEMENT_ENABLED, value).apply()

    var isMd3StyleEnabled: Boolean
        get() = prefs.getBoolean(KEY_MD3_STYLE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MD3_STYLE_ENABLED, value).apply()

    var iconCornerDp: Int
        get() = prefs.getInt(KEY_ICON_CORNER_DP, 15)
        set(value) = prefs.edit().putInt(KEY_ICON_CORNER_DP, value.coerceIn(0, 24)).apply()

    var rulesJson: String
        get() = prefs.getString(KEY_RULES_JSON, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_RULES_JSON, value).apply()

    var rulesCount: Int
        get() = prefs.getInt(KEY_RULES_COUNT, 0)
        private set(value) = prefs.edit().putInt(KEY_RULES_COUNT, value).apply()

    var rulesUpdatedAt: Long
        get() = prefs.getLong(KEY_RULES_UPDATED_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_RULES_UPDATED_AT, value).apply()

    fun updateRules(json: String, updatedAt: Long = System.currentTimeMillis()) {
        rulesJson = json
        rulesCount = parseRules(json).size
        rulesUpdatedAt = updatedAt
    }

    fun updateFrameworkSnapshot(
        frameworkName: String,
        frameworkVersion: String,
        apiVersion: Int,
        scopes: Collection<String>,
        connectedAt: Long = System.currentTimeMillis(),
    ) {
        prefs.edit()
            .putString(KEY_LAST_FRAMEWORK_NAME, frameworkName)
            .putString(KEY_LAST_FRAMEWORK_VERSION, frameworkVersion)
            .putInt(KEY_LAST_FRAMEWORK_API, apiVersion)
            .putString(KEY_LAST_SCOPE_LIST, scopes.joinToString("\n"))
            .putLong(KEY_LAST_FRAMEWORK_CONNECTED_AT, connectedAt)
            .apply()
    }

    fun readFrameworkSnapshot(): FrameworkSnapshot = FrameworkSnapshot(
        frameworkName = prefs.getString(KEY_LAST_FRAMEWORK_NAME, "").orEmpty(),
        frameworkVersion = prefs.getString(KEY_LAST_FRAMEWORK_VERSION, "").orEmpty(),
        apiVersion = prefs.getInt(KEY_LAST_FRAMEWORK_API, 0),
        scopes = prefs.getString(KEY_LAST_SCOPE_LIST, "").orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        lastConnectedAt = prefs.getLong(KEY_LAST_FRAMEWORK_CONNECTED_AT, 0L),
    )

    fun mirrorTo(remotePrefs: SharedPreferences): Boolean = remotePrefs.edit()
        .putBoolean(KEY_MODULE_ENABLED, isModuleEnabled)
        .putBoolean(KEY_ICON_ENHANCEMENT_ENABLED, isIconEnhancementEnabled)
        .putBoolean(KEY_MD3_STYLE_ENABLED, isMd3StyleEnabled)
        .putInt(KEY_ICON_CORNER_DP, iconCornerDp)
        .putInt(KEY_RULES_COUNT, rulesCount)
        .putLong(KEY_RULES_UPDATED_AT, rulesUpdatedAt)
        .commit()

    fun readHookSnapshot(remotePrefs: SharedPreferences?): HookSnapshot {
        if (remotePrefs == null) return defaultHookSnapshot()
        return HookSnapshot(
            moduleEnabled = remotePrefs.getBoolean(KEY_MODULE_ENABLED, true),
            iconEnhancementEnabled = remotePrefs.getBoolean(KEY_ICON_ENHANCEMENT_ENABLED, true),
            md3StyleEnabled = remotePrefs.getBoolean(KEY_MD3_STYLE_ENABLED, true),
            iconCornerDp = remotePrefs.getInt(KEY_ICON_CORNER_DP, 15).coerceIn(0, 24),
        )
    }

    fun defaultHookSnapshot() = HookSnapshot()

    fun parseRules(json: String): List<IconDataBean> {
        if (json.isBlank()) return emptyList()
        return buildList {
            runCatching {
                val array = JSONArray(json)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    IconDataBean.fromJson(item)?.let(::add)
                }
            }
        }
    }
}
