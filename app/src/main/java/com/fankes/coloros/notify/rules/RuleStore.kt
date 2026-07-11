package com.fankes.coloros.notify.rules

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy

object RuleStore {

    enum class IconSourceMode(val prefValue: String) {
        RuleLibrary("rule_library"),
        DesktopTheme("desktop_theme");

        companion object {
            fun fromPref(value: String?) =
                entries.firstOrNull { it.prefValue == value } ?: RuleLibrary
        }
    }

    data class ModuleConfig(
        val rulesEnabled: Boolean = true,
        val iconSourceMode: IconSourceMode = IconSourceMode.RuleLibrary,
        val panelIconReplacementEnabled: Boolean = true,
        val oplusPushSpecialHandlingEnabled: Boolean = true,
        val placeholderIconEnabled: Boolean = false,
    )

    data class MirrorSnapshot(
        val rulesJson: String,
        val rulesCount: Int,
        val rulesUpdatedAt: Long,
        val revision: Long,
        val contentSha256: String,
        val configValues: Map<String, Any>,
    )

    private data class CachedPayload(
        val pointerFileName: String?,
        val pointerSha256: String?,
        val previousPointerFileName: String?,
        val fileName: String?,
        val sha256: String,
        val json: String,
    )

    private data class ValidatedRules(
        val payload: CachedPayload,
        val catalog: RuleCatalog,
    )

    const val GROUP_CONFIG = "config"

    /** Legacy remote filename used only while migrating an existing installation. */
    const val LEGACY_RULES_FILE_NAME = "rules.json"
    const val KEY_RULES_FILE_NAME = "rules.file_name"
    const val KEY_PREVIOUS_RULES_FILE_NAME = "rules.previous_file_name"
    const val KEY_RULES_SHA256 = "rules.sha256"
    const val KEY_CONFIG_REVISION = "config.revision"

    /** Legacy local payload key. New versions keep only a content-addressed file pointer. */
    const val KEY_RULES_JSON = "rules_json"
    const val KEY_RULES_COUNT = "rules_count"
    const val KEY_RULES_UPDATED_AT = "rules_updated_at"
    const val KEY_RULES_ENABLED = "config.rules_enabled"
    const val KEY_ICON_SOURCE_MODE = "config.icon_source_mode"
    const val KEY_PANEL_ICON_REPLACEMENT_ENABLED = "config.panel_icon_replacement_enabled"
    const val KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED = "config.oplus_push_special_handling_enabled"
    const val KEY_PLACEHOLDER_ICON_ENABLED = "config.placeholder_icon_enabled"

    /** Previous releases used a wall-clock timestamp as a revision. Read for migration only. */
    const val KEY_CONFIG_UPDATED_AT = "config_updated_at"

    private const val PREFS_NAME = GROUP_CONFIG
    private const val LOCAL_RULE_DIRECTORY = "rules"
    private const val KEY_RULE_ENABLED_PREFIX = "rule.enabled."
    private const val KEY_RULE_ENABLED_ALL_PREFIX = "rule.enabled_all."
    private val OBSOLETE_KEYS = arrayOf(
        "md3_style_enabled",
        "icon_corner_dp",
        "module_enabled",
        "config.module_enabled",
        "icon_enhancement_enabled",
        "last_framework_name",
        "last_framework_version",
        "last_framework_api",
        "last_scope_list",
        "last_framework_connected_at",
    )

    private val mutationLock = Any()
    private val payloadLock = Any()

    @Volatile
    private var lastIssuedRevision = 0L

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedPayload: CachedPayload? = null

    @Volatile
    private var cachedCatalog: RuleCatalog? = null

    private lateinit var appContext: Context
    private lateinit var localStorage: LocalRuleStorage

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(mutationLock) {
            if (initialized) return
            if (!::appContext.isInitialized) {
                appContext = context.applicationContext
                localStorage = LocalRuleStorage(appContext.filesDir.resolve(LOCAL_RULE_DIRECTORY))
            }
            try {
                cleanupAndMigrate(prefs)
            } catch (exception: Exception) {
                AppDiagnostics.logger.report(
                    level = DiagnosticLevel.Error,
                    event = DiagnosticEvent.RulesSaveFailed,
                    message = "Unable to migrate the local rule catalog",
                    cause = exception,
                    attributes = mapOf("scope" to "migration"),
                )
            }
            initialized = true
        }
    }

    private val prefs: SharedPreferences
        get() {
            check(::appContext.isInitialized) { "RuleStore 尚未初始化" }
            return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

    val rulesCount: Int
        get() {
            val values = prefs.all.toMap()
            val storedSha256 = values[KEY_RULES_SHA256] as? String
            val payload = cachedPayload
            val catalog = cachedCatalog
            return catalog
                ?.takeIf {
                    payload != null &&
                        payload.pointerFileName == values.stringOrNull(KEY_RULES_FILE_NAME) &&
                        payload.pointerSha256 == storedSha256 &&
                        payload.previousPointerFileName == values.stringOrNull(KEY_PREVIOUS_RULES_FILE_NAME) &&
                        payload.sha256 == it.contentSha256
                }
                ?.size
                ?: (values[KEY_RULES_COUNT] as? Number)?.toInt()
                ?: 0
        }

    val rulesUpdatedAt: Long get() = prefs.getLong(KEY_RULES_UPDATED_AT, 0L)
    val moduleConfig: ModuleConfig get() = readModuleConfig(prefs)
    val resolvedCatalog: ResolvedRuleCatalog
        get() {
            val values = prefs.all.toMap()
            return loadValidatedRules(values).catalog.resolve(overridesFrom(values))
        }
    val rules: List<IconRule> get() = resolvedCatalog.rules

    /**
     * Validates and fsyncs a new immutable payload before publishing its local pointer. A failure
     * at any stage leaves the previous pointer and payload untouched.
     */
    @SuppressLint("UseKtx") // The commit result is part of the atomic publication contract.
    fun updateRules(
        json: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): RuleCatalog {
        val catalog = parseCatalog(json)
        require(catalog.size > 0) { "Rule catalog is empty" }

        val fileName: String
        val previousFile: String?
        synchronized(mutationLock) {
            fileName = localStorage.writeVerified(json, catalog.contentSha256)
            val values = prefs.all.toMap()
            val validatedCurrentFile = try {
                loadValidatedRules(values).payload.fileName
            } catch (_: Exception) {
                null
            }
            previousFile = VersionedRuleFiles.previousAfterValidatedUpdate(
                targetFile = fileName,
                validatedCurrentFile = validatedCurrentFile,
                storedPreviousFile = values.stringOrNull(KEY_PREVIOUS_RULES_FILE_NAME),
            )
            val revision = nextRevision(values.long(KEY_CONFIG_REVISION))
            check(
                prefs.edit()
                    .putString(KEY_RULES_FILE_NAME, fileName)
                    .putOptionalString(KEY_PREVIOUS_RULES_FILE_NAME, previousFile)
                    .putString(KEY_RULES_SHA256, catalog.contentSha256)
                    .putInt(KEY_RULES_COUNT, catalog.size)
                    .putLong(KEY_RULES_UPDATED_AT, updatedAt)
                    .putLong(KEY_CONFIG_REVISION, revision)
                    .remove(KEY_RULES_JSON)
                    .commit()
            ) { "Unable to persist the local rule pointer" }
            cachedPayload = CachedPayload(
                pointerFileName = fileName,
                pointerSha256 = catalog.contentSha256,
                previousPointerFileName = previousFile,
                fileName = fileName,
                sha256 = catalog.contentSha256,
                json = json,
            )
            cachedCatalog = catalog
            cleanupLocalFiles(fileName, previousFile, includeTemporary = false)
        }
        return catalog
    }

    /** Captures payload, metadata and overrides from exactly one preferences snapshot. */
    fun captureMirrorSnapshot(): MirrorSnapshot {
        val values = prefs.all.toMap()
        val validated = loadValidatedRules(values)
        val payload = validated.payload
        return MirrorSnapshot(
            rulesJson = payload.json,
            rulesCount = validated.catalog.size,
            rulesUpdatedAt = values.long(KEY_RULES_UPDATED_AT),
            revision = values.long(KEY_CONFIG_REVISION),
            contentSha256 = payload.sha256,
            configValues = values
                .filterKeys(::isMirroredConfigKey)
                .mapNotNull { (key, value) ->
                    when (value) {
                        is Boolean, is String -> key to value
                        else -> null
                    }
                }
                .toMap(),
        )
    }

    @SuppressLint("UseKtx") // RemotePreferences commit failure must remain observable.
    fun mirrorTo(
        remotePrefs: SharedPreferences,
        snapshot: MirrorSnapshot,
        fileName: String,
        previousFile: String?,
    ): Boolean = remotePrefs.edit().clear().apply {
        mirrorValues(snapshot, fileName, previousFile).forEach { (key, value) ->
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
            }
        }
    }.commit()

    /** Full desired remote state; retries never depend on RemotePreferences' optimistic cache. */
    internal fun mirrorValues(
        snapshot: MirrorSnapshot,
        fileName: String,
        previousFile: String?,
    ): Map<String, Any> = buildMap {
        put(KEY_RULES_FILE_NAME, fileName)
        previousFile?.let { put(KEY_PREVIOUS_RULES_FILE_NAME, it) }
        put(KEY_RULES_SHA256, snapshot.contentSha256)
        put(KEY_RULES_COUNT, snapshot.rulesCount)
        put(KEY_RULES_UPDATED_AT, snapshot.rulesUpdatedAt)
        put(KEY_CONFIG_REVISION, snapshot.revision)
        putAll(snapshot.configValues)
    }

    fun setRulesEnabled(enabled: Boolean) = editConfig {
        putBoolean(KEY_RULES_ENABLED, enabled)
    }

    fun setIconSourceMode(mode: IconSourceMode) = editConfig {
        putString(KEY_ICON_SOURCE_MODE, mode.prefValue)
    }

    fun setPanelIconReplacementEnabled(enabled: Boolean) = editConfig {
        putBoolean(KEY_PANEL_ICON_REPLACEMENT_ENABLED, enabled)
    }

    fun setOplusPushSpecialHandlingEnabled(enabled: Boolean) = editConfig {
        putBoolean(KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED, enabled)
    }

    fun setPlaceholderIconEnabled(enabled: Boolean) = editConfig {
        putBoolean(KEY_PLACEHOLDER_ICON_ENABLED, enabled)
    }

    fun setRuleEnabled(packageName: String, enabled: Boolean) = editConfig {
        putBoolean(ruleEnabledKey(packageName), enabled)
    }

    fun setRuleEnabledAll(packageName: String, enabledAll: Boolean) = editConfig {
        putBoolean(ruleEnabledAllKey(packageName), enabledAll)
    }

    fun setRulesEnabledAll(packageNames: Collection<String>, enabledAll: Boolean) {
        val distinctPackageNames = packageNames.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (distinctPackageNames.isEmpty()) return
        editConfig {
            distinctPackageNames.forEach { packageName ->
                putBoolean(ruleEnabledAllKey(packageName), enabledAll)
            }
        }
    }

    fun parseCatalog(
        json: String,
        expectedSha256: String? = null,
    ): RuleCatalog {
        val hash = RuleCatalogParser.validatedSha256(json)
        if (expectedSha256 != null && !hash.equals(expectedSha256, ignoreCase = true)) {
            throw RuleParseException(
                "Rule payload SHA-256 mismatch: expected ${expectedSha256.lowercase()}, actual $hash"
            )
        }
        cachedCatalog?.takeIf { it.contentSha256 == hash }?.let { return it }
        return RuleCatalogParser.parseTrusted(json, hash).also { cachedCatalog = it }
    }

    fun readModuleConfig(source: SharedPreferences?): ModuleConfig =
        readModuleConfig(source?.all.orEmpty())

    fun readModuleConfig(values: Map<String, *>): ModuleConfig = ModuleConfig(
        rulesEnabled = values.boolean(KEY_RULES_ENABLED, true),
        iconSourceMode = IconSourceMode.fromPref(values[KEY_ICON_SOURCE_MODE] as? String),
        panelIconReplacementEnabled = values.boolean(KEY_PANEL_ICON_REPLACEMENT_ENABLED, true),
        oplusPushSpecialHandlingEnabled = values.boolean(KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED, true),
        placeholderIconEnabled = values.boolean(KEY_PLACEHOLDER_ICON_ENABLED, false),
    )

    fun applyRuleOverrides(
        catalog: RuleCatalog,
        values: Map<String, *>,
    ): ResolvedRuleCatalog = catalog.resolve(overridesFrom(values))

    internal fun nextRevision(currentRevision: Long): Long = synchronized(mutationLock) {
        RevisionSequence.next(currentRevision, lastIssuedRevision)
            .also { lastIssuedRevision = it }
    }

    internal fun confirmCatalogMetadata(
        snapshot: MirrorSnapshot,
        actualCount: Int,
    ) {
        synchronized(mutationLock) {
            val values = prefs.all.toMap()
            if (values.long(KEY_CONFIG_REVISION) != snapshot.revision) return
            if (values[KEY_RULES_SHA256] != snapshot.contentSha256) return
            if ((values[KEY_RULES_COUNT] as? Number)?.toInt() == actualCount) return
            prefs.edit { putInt(KEY_RULES_COUNT, actualCount) }
        }
    }

    @SuppressLint("UseKtx") // Local durability must be known before scheduling publication.
    private fun editConfig(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(mutationLock) {
            val values = prefs.all.toMap()
            val revision = nextRevision(values.long(KEY_CONFIG_REVISION))
            // Local state is the source of truth. It must be durable before a remote publication
            // can claim that this revision succeeded.
            check(
                prefs.edit()
                    .apply(block)
                    .putLong(KEY_CONFIG_REVISION, revision)
                    .commit()
            ) { "Unable to persist local configuration" }
        }
    }

    private fun loadValidatedRules(values: Map<String, *>): ValidatedRules {
        val currentFile = values.stringOrNull(KEY_RULES_FILE_NAME)
        val previousFile = values.stringOrNull(KEY_PREVIOUS_RULES_FILE_NAME)
        val currentSha256 = values.stringOrNull(KEY_RULES_SHA256)
        val legacyJson = values[KEY_RULES_JSON] as? String
        val legacySha256 = if (currentFile == null) {
            legacyJson?.let(RuleCatalogParser::validatedSha256)
        } else {
            null
        }

        val payload = cachedPayload
        val catalog = cachedCatalog
        if (payload != null && catalog != null &&
            payload.pointerFileName == currentFile &&
            payload.pointerSha256 == currentSha256 &&
            payload.previousPointerFileName == previousFile &&
            payload.sha256 == catalog.contentSha256 &&
            (currentFile != null || payload.sha256 == (legacySha256 ?: RuleCatalogParser.sha256("")))
        ) {
            return ValidatedRules(payload, catalog)
        }

        return synchronized(payloadLock) {
            val synchronizedPayload = cachedPayload
            val synchronizedCatalog = cachedCatalog
            if (synchronizedPayload != null && synchronizedCatalog != null &&
                synchronizedPayload.pointerFileName == currentFile &&
                synchronizedPayload.pointerSha256 == currentSha256 &&
                synchronizedPayload.previousPointerFileName == previousFile &&
                synchronizedPayload.sha256 == synchronizedCatalog.contentSha256 &&
                (currentFile != null || synchronizedPayload.sha256 ==
                    (legacySha256 ?: RuleCatalogParser.sha256("")))
            ) {
                return@synchronized ValidatedRules(synchronizedPayload, synchronizedCatalog)
            }

            val sources = ArrayList<RulePayloadFallback.Candidate>(3)
            if (currentFile != null) {
                if (currentSha256 != null && isSha256(currentSha256) &&
                    currentFile == VersionedRuleFiles.nameFor(currentSha256)
                ) {
                    sources += RulePayloadFallback.Candidate(currentFile, currentSha256) {
                        localStorage.readVerified(currentFile, currentSha256)
                    }
                } else {
                    sources += RulePayloadFallback.Candidate(
                        currentFile,
                        currentSha256.orEmpty(),
                    ) { throw IllegalStateException("Local current rule pointer is incomplete") }
                }
            } else if (currentSha256 != null) {
                sources += RulePayloadFallback.Candidate(null, currentSha256) {
                    throw IllegalStateException("Local rule SHA-256 has no file pointer")
                }
            }

            if (previousFile != null && previousFile != currentFile) {
                val previousSha256 = VersionedRuleFiles.shaFromName(previousFile)
                if (previousSha256 != null) {
                    sources += RulePayloadFallback.Candidate(previousFile, previousSha256) {
                        localStorage.readVerified(previousFile, previousSha256)
                    }
                } else {
                    sources += RulePayloadFallback.Candidate(previousFile, "") {
                        throw IllegalStateException("Local previous rule pointer is invalid")
                    }
                }
            }

            if (legacyJson != null && currentFile == null) {
                sources += RulePayloadFallback.Candidate(
                    null,
                    requireNotNull(legacySha256),
                ) { legacyJson }
            }
            if (sources.isEmpty()) {
                val emptySha256 = RuleCatalogParser.sha256("")
                sources += RulePayloadFallback.Candidate(null, emptySha256) { "" }
            }

            val selection = try {
                RulePayloadFallback.load(
                    candidates = sources,
                ) { json, sha256 ->
                    cachedCatalog
                        ?.takeIf { it.contentSha256 == sha256 }
                        ?: RuleCatalogParser.parseTrusted(json, sha256)
                }
            } catch (exception: Exception) {
                AppDiagnostics.logger.report(
                    level = DiagnosticLevel.Error,
                    event = DiagnosticEvent.RulesLoadFailed,
                    message = "Unable to load current or previous local rule catalog",
                    cause = exception,
                    attributes = mapOf("revision" to values.long(KEY_CONFIG_REVISION)),
                    occurrence = OccurrencePolicy.Once("local:${values.long(KEY_CONFIG_REVISION)}"),
                )
                throw exception
            }
            val selectedPayload = CachedPayload(
                pointerFileName = currentFile,
                pointerSha256 = currentSha256,
                previousPointerFileName = previousFile,
                fileName = selection.candidate.fileName,
                sha256 = selection.candidate.sha256,
                json = selection.json,
            )
            cachedPayload = selectedPayload
            cachedCatalog = selection.value
            if (selection.failures.isNotEmpty()) {
                reportLocalFallback(
                    revision = values.long(KEY_CONFIG_REVISION),
                    failedFile = currentFile,
                    fallbackFile = selection.candidate.fileName,
                    cause = selection.failures.first(),
                )
            }
            ValidatedRules(selectedPayload, selection.value)
        }
    }

    private fun reportLocalFallback(
        revision: Long,
        failedFile: String?,
        fallbackFile: String?,
        cause: Exception,
    ) {
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Warning,
            event = DiagnosticEvent.RulesLoadFailed,
            message = "Current local rule catalog is invalid; using the previous version",
            cause = cause,
            attributes = mapOf(
                "revision" to revision,
                "current" to failedFile,
                "fallback" to fallbackFile,
            ),
            occurrence = OccurrencePolicy.Once("local-fallback:$revision:$failedFile"),
        )
    }

    @SuppressLint("UseKtx") // Migration removes legacy state only after a successful commit.
    private fun cleanupAndMigrate(localPrefs: SharedPreferences) {
        val values = localPrefs.all.toMap()
        val migration = LocalRuleMigration.plan(values)
        val legacyRevision = values.long(KEY_CONFIG_UPDATED_AT)
        val existingRevision = values.long(KEY_CONFIG_REVISION)
        val hasExistingState = values.keys.any {
            it == KEY_RULES_JSON || it == KEY_RULES_COUNT || it == KEY_RULES_UPDATED_AT || isMirroredConfigKey(it)
        }
        val migratedRevision = when {
            existingRevision > 0L -> existingRevision
            legacyRevision > 0L -> legacyRevision
            hasExistingState -> 1L
            else -> 0L
        }
        lastIssuedRevision = maxOf(lastIssuedRevision, migratedRevision)

        var migratedPayload: CachedPayload? = null
        migration.payloadToMigrate?.let { legacyJson ->
            if (legacyJson.isNotBlank()) {
                val sha256 = RuleCatalogParser.validatedSha256(legacyJson)
                val fileName = localStorage.writeVerified(legacyJson, sha256)
                migratedPayload = CachedPayload(
                    pointerFileName = fileName,
                    pointerSha256 = sha256,
                    previousPointerFileName = null,
                    fileName = fileName,
                    sha256 = sha256,
                    json = legacyJson,
                )
            } else {
                migratedPayload = CachedPayload(
                    pointerFileName = null,
                    pointerSha256 = null,
                    previousPointerFileName = null,
                    fileName = null,
                    sha256 = RuleCatalogParser.sha256(""),
                    json = "",
                )
            }
        }

        val needsCleanup = OBSOLETE_KEYS.any(values::containsKey) ||
            values.containsKey(KEY_CONFIG_UPDATED_AT) ||
            migration.removeLegacyKey ||
            existingRevision != migratedRevision
        if (needsCleanup) {
            check(localPrefs.edit().apply {
                OBSOLETE_KEYS.forEach(::remove)
                remove(KEY_CONFIG_UPDATED_AT)
                remove(KEY_RULES_JSON)
                if (migratedRevision > 0L) putLong(KEY_CONFIG_REVISION, migratedRevision)
                migratedPayload?.let { payload ->
                    if (payload.fileName == null) {
                        remove(KEY_RULES_FILE_NAME)
                        remove(KEY_PREVIOUS_RULES_FILE_NAME)
                        remove(KEY_RULES_SHA256)
                        putInt(KEY_RULES_COUNT, 0)
                    } else {
                        putString(KEY_RULES_FILE_NAME, payload.fileName)
                        remove(KEY_PREVIOUS_RULES_FILE_NAME)
                        putString(KEY_RULES_SHA256, payload.sha256)
                    }
                }
            }.commit()) { "Unable to migrate local configuration" }
        }

        migratedPayload?.let { cachedPayload = it }
        val currentFile = (migratedPayload?.fileName ?: values.stringOrNull(KEY_RULES_FILE_NAME))
        if (currentFile != null) {
            val previousFile = if (migratedPayload != null) {
                null
            } else {
                values.stringOrNull(KEY_PREVIOUS_RULES_FILE_NAME)
            }
            cleanupLocalFiles(currentFile, previousFile, includeTemporary = true)
        }
    }

    private fun cleanupLocalFiles(
        currentFile: String,
        previousFile: String?,
        includeTemporary: Boolean,
    ) {
        val failedFiles = localStorage.cleanup(currentFile, previousFile, includeTemporary)
        if (failedFiles.isEmpty()) return
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Warning,
            event = DiagnosticEvent.RulesSaveFailed,
            message = "Unable to remove obsolete local rule files",
            attributes = mapOf("files" to failedFiles.joinToString()),
        )
    }

    private fun overridesFrom(values: Map<String, *>): RuleOverrides {
        val enabled = HashMap<String, Boolean>()
        val enabledAll = HashMap<String, Boolean>()
        values.forEach { (key, value) ->
            when {
                key.startsWith(KEY_RULE_ENABLED_PREFIX) && value is Boolean ->
                    enabled[key.removePrefix(KEY_RULE_ENABLED_PREFIX)] = value

                key.startsWith(KEY_RULE_ENABLED_ALL_PREFIX) && value is Boolean ->
                    enabledAll[key.removePrefix(KEY_RULE_ENABLED_ALL_PREFIX)] = value
            }
        }
        return RuleOverrides(enabled, enabledAll)
    }

    private fun isMirroredConfigKey(key: String) =
        key == KEY_RULES_ENABLED ||
            key == KEY_ICON_SOURCE_MODE ||
            key == KEY_PANEL_ICON_REPLACEMENT_ENABLED ||
            key == KEY_OPLUS_PUSH_SPECIAL_HANDLING_ENABLED ||
            key == KEY_PLACEHOLDER_ICON_ENABLED ||
            key.startsWith(KEY_RULE_ENABLED_PREFIX) ||
            key.startsWith(KEY_RULE_ENABLED_ALL_PREFIX)

    private fun ruleEnabledKey(packageName: String) = KEY_RULE_ENABLED_PREFIX + packageName
    private fun ruleEnabledAllKey(packageName: String) = KEY_RULE_ENABLED_ALL_PREFIX + packageName

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun SharedPreferences.Editor.putOptionalString(key: String, value: String?) = apply {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun Map<String, *>.stringOrNull(key: String): String? =
        (this[key] as? String)?.takeIf(String::isNotBlank)

    private fun Map<String, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, *>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default
}
