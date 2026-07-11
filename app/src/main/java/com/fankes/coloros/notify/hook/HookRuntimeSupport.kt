package com.fankes.coloros.notify.hook

import com.fankes.coloros.notify.core.ModuleInfo
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.diagnostics.DiagnosticRecord
import com.fankes.coloros.notify.diagnostics.DiagnosticSink
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.diagnostics.OccurrencePolicy
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

internal class XposedDiagnosticSink(
    private val xposed: XposedInterface,
    private val processName: String,
) : DiagnosticSink {
    override fun write(record: DiagnosticRecord) {
        val contextualRecord = record.copy(
            attributes = mapOf("process" to processName) + record.attributes,
        )
        val cause = contextualRecord.cause
        if (cause == null) {
            xposed.log(contextualRecord.level.priority, ModuleInfo.LOG_TAG, contextualRecord.render())
        } else {
            xposed.log(
                contextualRecord.level.priority,
                ModuleInfo.LOG_TAG,
                contextualRecord.render(),
                cause,
            )
        }
    }
}

internal class HookRegistrar(
    private val xposed: XposedInterface,
    private val diagnostics: Diagnostics,
    private val processName: String,
) {
    /**
     * Installs one independently degradable hook. Framework [Error]s intentionally escape.
     */
    fun install(
        executable: Executable,
        id: String,
        interceptor: (XposedInterface.Chain) -> Any?,
    ): Boolean = try {
        xposed.hook(executable)
            .setId("${ModuleInfo.LOG_TAG}.$id")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker(interceptor))
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.HookInstalled,
            message = "Hook 已安装",
            attributes = attributes(id),
            occurrence = OccurrencePolicy.Once("$processName:$id"),
        )
        true
    } catch (exception: Exception) {
        diagnostics.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.HookInstallFailed,
            message = "Hook 安装失败",
            cause = exception,
            attributes = attributes(id),
            occurrence = OccurrencePolicy.Once("$processName:$id"),
        )
        false
    }

    private fun attributes(id: String) = mapOf(
        "hook" to id,
    )
}

internal fun Diagnostics.memberMissing(
    scope: String,
    message: String,
    cause: Throwable? = null,
) {
    report(
        level = DiagnosticLevel.Warning,
        event = DiagnosticEvent.MemberMissing,
        message = message,
        cause = cause,
        attributes = mapOf("scope" to scope),
        occurrence = OccurrencePolicy.Once(scope),
    )
}

internal fun Diagnostics.runtimeFailure(
    scope: String,
    message: String,
    cause: Throwable,
    revision: Long? = null,
) {
    report(
        level = DiagnosticLevel.Error,
        event = DiagnosticEvent.HookRuntimeFailed,
        message = message,
        cause = cause,
        attributes = buildMap {
            put("scope", scope)
            revision?.let { put("revision", it) }
        },
        occurrence = OccurrencePolicy.RateLimited(scope, RUNTIME_LOG_INTERVAL_MILLIS),
    )
}

internal fun Diagnostics.installationFailure(
    scope: String,
    message: String,
    cause: Exception,
) {
    report(
        level = DiagnosticLevel.Error,
        event = DiagnosticEvent.HookInstallFailed,
        message = message,
        cause = cause,
        attributes = mapOf("scope" to scope),
        occurrence = OccurrencePolicy.Once(scope),
    )
}

private const val RUNTIME_LOG_INTERVAL_MILLIS = 60_000L
