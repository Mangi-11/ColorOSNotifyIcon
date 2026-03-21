package com.fankes.coloros.notify.utils.tool

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object SystemUiControl {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
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
        executor.execute {
            val result = runCatching {
                val process = ProcessBuilder("su", "-c", restartCommand)
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val exitCode = process.waitFor()
                check(exitCode == 0) { output.ifBlank { "重启 SystemUI 执行失败" } }
            }
            mainHandler.post { onResult(result.map { Unit }) }
        }
    }
}
