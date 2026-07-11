package com.fankes.coloros.notify.framework

import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SystemUiRestarter {

    class RestartFailure internal constructor(cause: Exception) :
        Exception("Unable to restart SystemUI", cause) {
        val userMessage: String = "无法执行 SystemUI 重启命令"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "systemui-restarter")
    }
    private val restartCommand = """
        pid=$(/system/bin/pidof com.android.systemui 2>/dev/null)
        if [ -n "${'$'}pid" ]; then
          /system/bin/kill -9 ${'$'}pid
          echo "killed:${'$'}pid"
          exit 0
        fi
        /system/bin/pkill -f com.android.systemui >/dev/null 2>&1 && { echo "pkill"; exit 0; }
        /system/bin/killall com.android.systemui >/dev/null 2>&1 && { echo "killall"; exit 0; }
        echo "not_found"
        exit 1
    """.trimIndent()

    fun restartSystemUi(onResult: (Result<Unit>) -> Unit) {
        try {
            executor.execute {
                deliver(restartResult(), onResult)
            }
        } catch (exception: Exception) {
            deliver(failureResult(exception), onResult)
        }
    }

    private fun restartResult(): Result<Unit> = try {
        restartBlocking()
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.SystemUiRestarted,
            message = "SystemUI restart requested",
        )
        Result.success(Unit)
    } catch (exception: Exception) {
        failureResult(exception)
    }

    private fun failureResult(cause: Exception): Result<Unit> {
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        val failure = RestartFailure(cause)
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.SystemUiRestartFailed,
            message = failure.message.orEmpty(),
            cause = cause,
        )
        return Result.failure(failure)
    }

    private fun deliver(result: Result<Unit>, callback: (Result<Unit>) -> Unit) {
        MainThreadCallbacks.dispatch("systemui_restart") { callback(result) }
    }

    private fun restartBlocking() {
        val process = ProcessBuilder("su", "-c", restartCommand)
            .redirectErrorStream(true)
            .start()
        try {
            if (!process.waitFor(RESTART_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw TimeoutException("SystemUI restart command timed out")
            }
            // Drain the merged stream without exposing shell output to UI or diagnostics.
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            check(process.exitValue() == 0) {
                "SystemUI restart command exited with ${process.exitValue()}"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private const val RESTART_TIMEOUT_SECONDS = 10L
}
