package com.fankes.coloros.notify.hook.systemui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import com.fankes.coloros.notify.hook.icon.NotificationIconResolver
import com.fankes.coloros.notify.hook.icon.OplusIconConfigurationReader
import com.fankes.coloros.notify.hook.icon.ThemeIconProvider
import com.fankes.coloros.notify.hook.runtimeFailure
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.FallbackSequence
import com.fankes.coloros.notify.rules.RuleCatalog
import com.fankes.coloros.notify.rules.RulePayloadIO
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.rules.VersionedRuleFiles
import io.github.libxposed.api.XposedInterface
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeSnapshot(
    val revision: Long,
    val config: RuleStore.ModuleConfig,
    val rulesFileName: String?,
    val ruleCatalog: RuleCatalog,
    val rules: Map<String, IconRule>,
    val resolver: NotificationIconResolver,
)

internal class SystemUiConfiguration(
    private val xposed: XposedInterface,
    private val diagnostics: Diagnostics,
    private val processName: String,
    private val themeIcons: ThemeIconProvider,
    private val iconConfiguration: OplusIconConfigurationReader,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private data class CatalogCandidate(
        val fileName: String,
        val sha256: String?,
        val invalidReason: String? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ColorOSNotifyIcon-config").apply { isDaemon = true }
    }
    private val latestRequest = AtomicLong()
    private val remotePreferences = openRemotePreferences()
    private var onPublished: (RuntimeSnapshot) -> Unit = {}

    @Volatile
    var snapshot: RuntimeSnapshot = createSnapshot(
        revision = 0L,
        config = RuleStore.ModuleConfig(),
        rulesFileName = null,
        catalog = RuleStore.parseCatalog(""),
        rules = emptyMap(),
    )
        private set

    fun start(onPublished: (RuntimeSnapshot) -> Unit) {
        this.onPublished = onPublished
        val preferences = remotePreferences ?: return
        try {
            preferences.registerOnSharedPreferenceChangeListener(this)
        } catch (exception: Exception) {
            reportLoadFailure(
                scope = "listener",
                revision = preferences.revisionOrZero(),
                message = "注册远程配置监听失败，当前进程将使用启动时配置",
                cause = exception,
            )
        }
        requestReload(preferences.revisionOrZero())
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key != RuleStore.KEY_CONFIG_REVISION) return
        requestReload(sharedPreferences.revisionOrZero())
    }

    fun isCurrent(candidate: RuntimeSnapshot): Boolean =
        snapshot.let { current -> current === candidate && current.revision == candidate.revision }

    private fun requestReload(observedRevision: Long) {
        val preferences = remotePreferences ?: return
        val request = latestRequest.incrementAndGet()
        try {
            loader.execute {
                val candidate = try {
                    loadSnapshot(preferences)
                } catch (exception: Exception) {
                    if (latestRequest.get() == request) {
                        reportLoadFailure(
                            scope = "snapshot",
                            revision = observedRevision,
                            message = "远程配置快照加载失败，继续使用上一版本",
                            cause = exception,
                        )
                    }
                    return@execute
                }
                if (latestRequest.get() != request) return@execute

                try {
                    themeIcons.clearCache()
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "config:theme_cache_clear",
                        message = "清理主题图标缓存失败，继续发布新配置快照",
                        cause = exception,
                        revision = candidate.revision,
                    )
                }
                snapshot = candidate
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.ConfigLoaded,
                    message = "SystemUI 配置快照已发布",
                    attributes = mapOf(
                        "revision" to candidate.revision,
                        "rules" to candidate.rules.size,
                    ),
                    occurrence = OccurrencePolicy.Once("$processName:${candidate.revision}"),
                )
                var postFailure: Exception? = null
                val posted = try {
                    mainHandler.post {
                        if (!isCurrent(candidate)) return@post
                        try {
                            onPublished(candidate)
                        } catch (exception: Exception) {
                            diagnostics.runtimeFailure(
                                scope = "config:on_published",
                                message = "配置快照已发布，但主线程刷新失败",
                                cause = exception,
                                revision = candidate.revision,
                            )
                        }
                    }
                } catch (exception: Exception) {
                    postFailure = exception
                    false
                }
                if (!posted) {
                    diagnostics.runtimeFailure(
                        scope = "config:post_refresh",
                        message = "配置快照已发布，但无法调度 SystemUI 主线程刷新",
                        cause = postFailure
                            ?: IllegalStateException("Main thread rejected the configuration refresh"),
                        revision = candidate.revision,
                    )
                }
            }
        } catch (exception: Exception) {
            reportLoadFailure(
                scope = "schedule",
                revision = observedRevision,
                message = "远程配置加载任务无法调度，继续使用上一版本",
                cause = exception,
            )
        }
    }

    private fun loadSnapshot(preferences: SharedPreferences): RuntimeSnapshot {
        val values = preferences.all.toMap()
        val revision = (values[RuleStore.KEY_CONFIG_REVISION] as? Number)?.toLong() ?: 0L
        val publishedFileName = (values[RuleStore.KEY_RULES_FILE_NAME] as? String)
            ?.takeIf(String::isNotBlank)
        val expectedSha256 = (values[RuleStore.KEY_RULES_SHA256] as? String)
            ?.takeIf(String::isNotBlank)
        val previousFileName = (values[RuleStore.KEY_PREVIOUS_RULES_FILE_NAME] as? String)
            ?.takeIf(String::isNotBlank)
        val hasLegacyCatalog = ((values[RuleStore.KEY_RULES_COUNT] as? Number)?.toInt() ?: 0) > 0 ||
            ((values[RuleStore.KEY_CONFIG_UPDATED_AT] as? Number)?.toLong() ?: 0L) > 0L
        if (revision == 0L && publishedFileName == null && expectedSha256 == null && !hasLegacyCatalog) {
            return createSnapshot(
                revision = revision,
                config = RuleStore.readModuleConfig(values),
                rulesFileName = null,
                catalog = RuleStore.parseCatalog(""),
                rules = emptyMap(),
            )
        }
        val current = snapshot
        val candidates = buildList {
            if (publishedFileName != null) {
                val pointerSha256 = VersionedRuleFiles.shaFromName(publishedFileName)
                add(
                    CatalogCandidate(
                        fileName = publishedFileName,
                        sha256 = expectedSha256,
                        invalidReason = "Published remote rule pointer is invalid".takeUnless {
                            expectedSha256 != null && pointerSha256 == expectedSha256
                        },
                    )
                )
            } else if (expectedSha256 != null) {
                add(
                    CatalogCandidate(
                        fileName = "",
                        sha256 = expectedSha256,
                        invalidReason = "Remote rule SHA-256 has no file pointer",
                    )
                )
            }
            if (previousFileName != null && previousFileName != publishedFileName) {
                val previousSha256 = VersionedRuleFiles.shaFromName(previousFileName)
                add(
                    CatalogCandidate(
                        fileName = previousFileName,
                        sha256 = previousSha256,
                        invalidReason = "Previous remote rule pointer is invalid"
                            .takeIf { previousSha256 == null },
                    )
                )
            }
            if (publishedFileName == null && hasLegacyCatalog) {
                add(CatalogCandidate(RuleStore.LEGACY_RULES_FILE_NAME, sha256 = null))
            }
        }
        val selection = FallbackSequence.firstValid(
            candidates.map { candidate ->
                FallbackSequence.Attempt(candidate) {
                    candidate.invalidReason?.let { throw IllegalStateException(it) }
                    current.ruleCatalog.takeIf {
                        current.rulesFileName == candidate.fileName &&
                            candidate.sha256 != null &&
                            it.contentSha256.equals(candidate.sha256, ignoreCase = true)
                    } ?: RuleStore.parseCatalog(
                        readRemoteFile(candidate.fileName),
                        candidate.sha256,
                    )
                }
            }
        )
        if (selection.failures.isNotEmpty()) {
            reportLoadFailure(
                scope = "fallback",
                revision = revision,
                message = "当前远程规则无效，已回退到上一版本",
                cause = selection.failures.first(),
                level = DiagnosticLevel.Warning,
            )
        }
        val resolved = RuleStore.applyRuleOverrides(selection.value, values)
        return createSnapshot(
            revision = revision,
            config = RuleStore.readModuleConfig(values),
            rulesFileName = selection.key.fileName,
            catalog = selection.value,
            rules = resolved.byPackage.toMap(),
        )
    }

    private fun createSnapshot(
        revision: Long,
        config: RuleStore.ModuleConfig,
        rulesFileName: String?,
        catalog: RuleCatalog,
        rules: Map<String, IconRule>,
    ): RuntimeSnapshot = RuntimeSnapshot(
        revision = revision,
        config = config,
        rulesFileName = rulesFileName,
        ruleCatalog = catalog,
        rules = rules,
        resolver = NotificationIconResolver(
            config = config,
            rules = rules,
            themeIcons = themeIcons,
            iconConfiguration = iconConfiguration,
            diagnostics = diagnostics,
            revision = revision,
        ),
    )

    private fun readRemoteFile(name: String): String =
        xposed.openRemoteFile(name).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { RulePayloadIO.readUtf8(it) }
        }

    private fun openRemotePreferences(): SharedPreferences? = try {
        xposed.getRemotePreferences(RuleStore.GROUP_CONFIG)
    } catch (exception: Exception) {
        reportLoadFailure(
            scope = "preferences",
            revision = 0L,
            message = "无法打开远程配置，SystemUI 将使用安全默认值",
            cause = exception,
        )
        null
    }

    private fun SharedPreferences.revisionOrZero(): Long = try {
        getLong(RuleStore.KEY_CONFIG_REVISION, 0L)
    } catch (exception: Exception) {
        reportLoadFailure(
            scope = "revision",
            revision = 0L,
            message = "读取远程配置 revision 失败",
            cause = exception,
        )
        0L
    }

    private fun reportLoadFailure(
        scope: String,
        revision: Long,
        message: String,
        cause: Exception,
        level: DiagnosticLevel = DiagnosticLevel.Error,
    ) {
        diagnostics.report(
            level = level,
            event = DiagnosticEvent.ConfigLoadFailed,
            message = message,
            cause = cause,
            attributes = mapOf(
                "revision" to revision,
                "scope" to scope,
            ),
            occurrence = OccurrencePolicy.Once("$processName:$scope:$revision"),
        )
    }
}
