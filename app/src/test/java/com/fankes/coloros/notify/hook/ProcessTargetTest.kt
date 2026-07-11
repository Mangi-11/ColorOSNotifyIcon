package com.fankes.coloros.notify.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessTargetTest {

    @Test
    fun `system server flag wins over process name`() {
        assertEquals(
            ProcessTarget.SystemServer,
            resolveProcessTarget(isSystemServer = true, processName = "system_server"),
        )
    }

    @Test
    fun `only the exact SystemUI main process is accepted`() {
        assertEquals(
            ProcessTarget.SystemUiMain,
            resolveProcessTarget(isSystemServer = false, processName = "com.android.systemui"),
        )
    }

    @Test
    fun `SystemUI secondary processes are ignored`() {
        listOf(
            "com.android.systemui:ui",
            "com.android.systemui:screenshot",
            "com.android.systemui:appclips.screenshot",
            "com.android.systemui:fgservices",
            "com.android.systemui:tuner",
            "com.android.systemui:sweetsweetdesserts",
        ).forEach { processName ->
            assertEquals(
                processName,
                ProcessTarget.Ignore,
                resolveProcessTarget(isSystemServer = false, processName = processName),
            )
        }
    }
}
