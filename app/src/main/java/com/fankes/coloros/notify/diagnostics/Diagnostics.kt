package com.fankes.coloros.notify.diagnostics

import android.util.Log
import com.fankes.coloros.notify.core.ModuleInfo
import java.util.concurrent.ConcurrentHashMap

internal enum class DiagnosticLevel(val priority: Int) {
    Debug(Log.DEBUG),
    Info(Log.INFO),
    Warning(Log.WARN),
    Error(Log.ERROR),
}

internal enum class DiagnosticEvent(val id: String) {
    ModuleLoaded("module.loaded"),
    ProcessIgnored("module.process_ignored"),
    LifecycleDetached("module.lifecycle_detached"),
    MemberMissing("hook.member_missing"),
    HookInstalled("hook.installed"),
    HookInstallFailed("hook.install_failed"),
    HookRuntimeFailed("hook.runtime_failed"),
    IconRenderClamped("icon.render_clamped"),
    ConfigLoaded("config.loaded"),
    ConfigLoadFailed("config.load_failed"),
    ConfigPublished("config.published"),
    ConfigPublishFailed("config.publish_failed"),
    AppCallbackFailed("app.callback_failed"),
    RulesDownloaded("rules.downloaded"),
    RulesDownloadFailed("rules.download_failed"),
    RulesParseFailed("rules.parse_failed"),
    RulesLoadFailed("rules.load_failed"),
    IconDecodeFailed("rules.icon_decode_failed"),
    RulesSaveFailed("rules.save_failed"),
    ServiceConnected("service.connected"),
    ServiceDisconnected("service.disconnected"),
    ServiceQueryFailed("service.query_failed"),
    SystemInfoReadFailed("system_info.read_failed"),
    SystemUiRestarted("systemui.restarted"),
    SystemUiRestartFailed("systemui.restart_failed"),
}

internal sealed interface OccurrencePolicy {
    data object Always : OccurrencePolicy

    data class Once(val scope: String) : OccurrencePolicy

    data class RateLimited(
        val scope: String,
        val intervalMillis: Long,
    ) : OccurrencePolicy
}

internal data class DiagnosticRecord(
    val level: DiagnosticLevel,
    val event: DiagnosticEvent,
    val message: String,
    val cause: Throwable? = null,
    val attributes: Map<String, Any?> = emptyMap(),
) {
    fun render(): String = buildString {
        append("event=").append(event.id)
        attributes.toSortedMap().forEach { (key, value) ->
            append(' ').append(key).append('=').append(value.toLogValue())
        }
        append(" message=").append(message.toLogValue())
    }

    private fun Any?.toLogValue(): String = when (this) {
        null -> "null"
        is Number, is Boolean -> toString()
        else -> '"' + (
            toString()
                .take(MAX_VALUE_LENGTH)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace(CONTROL_CHARACTER) { match ->
                    "\\u" + match.value.single().code.toString(16).padStart(4, '0')
                }
            ) + '"'
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 512
        val CONTROL_CHARACTER = Regex("[\\u0000-\\u001f\\u007f]")
    }
}

internal fun interface DiagnosticSink {
    fun write(record: DiagnosticRecord)
}

internal class Diagnostics(
    private val sink: DiagnosticSink,
    private val elapsedRealtime: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private val emittedOnce = ConcurrentHashMap.newKeySet<String>()
    private val lastEmission = ConcurrentHashMap<String, Long>()

    fun report(
        level: DiagnosticLevel,
        event: DiagnosticEvent,
        message: String,
        cause: Throwable? = null,
        attributes: Map<String, Any?> = emptyMap(),
        occurrence: OccurrencePolicy = OccurrencePolicy.Always,
    ) {
        if (!shouldEmit(event, occurrence)) return
        sink.write(
            DiagnosticRecord(
                level = level,
                event = event,
                message = message,
                cause = cause,
                attributes = attributes,
            )
        )
    }

    private fun shouldEmit(event: DiagnosticEvent, occurrence: OccurrencePolicy): Boolean = when (occurrence) {
        OccurrencePolicy.Always -> true
        is OccurrencePolicy.Once -> emittedOnce.add("${event.id}:${occurrence.scope}")
        is OccurrencePolicy.RateLimited -> {
            require(occurrence.intervalMillis >= 0L) { "intervalMillis must not be negative" }
            val key = "${event.id}:${occurrence.scope}"
            val now = elapsedRealtime()
            var accepted = false
            lastEmission.compute(key) { _, previous ->
                if (previous == null || now - previous >= occurrence.intervalMillis) {
                    accepted = true
                    now
                } else {
                    previous
                }
            }
            accepted
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal class AndroidLogSink(
    private val tag: String = ModuleInfo.LOG_TAG,
) : DiagnosticSink {
    override fun write(record: DiagnosticRecord) {
        val message = record.cause?.let {
            record.render() + '\n' + Log.getStackTraceString(it)
        } ?: record.render()
        Log.println(record.level.priority, tag, message)
    }
}

internal object AppDiagnostics {
    val logger = Diagnostics(AndroidLogSink())
}
