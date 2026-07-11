package com.fankes.coloros.notify.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `debug entry point emits a structured debug record`() {
        val records = mutableListOf<DiagnosticRecord>()
        val diagnostics = Diagnostics(DiagnosticSink { records += it })

        diagnostics.debug(
            event = DiagnosticEvent.HookInstalled,
            message = "installed",
            attributes = mapOf("hook" to "statusbar"),
            occurrence = OccurrencePolicy.Always,
        )

        assertEquals(1, records.size)
        assertEquals(DiagnosticLevel.Debug, records.single().level)
        assertEquals(DiagnosticEvent.HookInstalled, records.single().event)
        assertEquals(mapOf("hook" to "statusbar"), records.single().attributes)
    }

    @Test
    fun `once policy is scoped by event and caller scope`() {
        val records = mutableListOf<DiagnosticRecord>()
        val diagnostics = Diagnostics(DiagnosticSink { records += it })

        repeat(2) {
            diagnostics.report(
                level = DiagnosticLevel.Warning,
                event = DiagnosticEvent.MemberMissing,
                message = "missing",
                occurrence = OccurrencePolicy.Once("statusbar.icon"),
            )
        }
        diagnostics.report(
            level = DiagnosticLevel.Warning,
            event = DiagnosticEvent.MemberMissing,
            message = "another",
            occurrence = OccurrencePolicy.Once("panel.icon"),
        )

        assertEquals(2, records.size)
    }

    @Test
    fun `rate limit accepts the first and next elapsed window`() {
        var now = 100L
        val records = mutableListOf<DiagnosticRecord>()
        val diagnostics = Diagnostics(DiagnosticSink { records += it }) { now }
        val policy = OccurrencePolicy.RateLimited("statusbar", intervalMillis = 1_000L)

        diagnostics.report(DiagnosticLevel.Error, DiagnosticEvent.HookRuntimeFailed, "first", occurrence = policy)
        now = 1_099L
        diagnostics.report(DiagnosticLevel.Error, DiagnosticEvent.HookRuntimeFailed, "suppressed", occurrence = policy)
        now = 1_100L
        diagnostics.report(DiagnosticLevel.Error, DiagnosticEvent.HookRuntimeFailed, "second", occurrence = policy)

        assertEquals(listOf("first", "second"), records.map(DiagnosticRecord::message))
    }

    @Test
    fun `different events do not share a rate limit bucket`() {
        val records = mutableListOf<DiagnosticRecord>()
        val diagnostics = Diagnostics(DiagnosticSink { records += it }) { 0L }
        val policy = OccurrencePolicy.RateLimited("shared", intervalMillis = 1_000L)

        diagnostics.report(DiagnosticLevel.Error, DiagnosticEvent.HookRuntimeFailed, "hook", occurrence = policy)
        diagnostics.report(DiagnosticLevel.Error, DiagnosticEvent.ConfigLoadFailed, "config", occurrence = policy)

        assertEquals(2, records.size)
    }

    @Test
    fun `record rendering is stable escaped and sorted`() {
        val rendered = DiagnosticRecord(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.ConfigLoadFailed,
            message = "line one\nline two",
            attributes = mapOf("revision" to 7L, "feature" to "rules"),
        ).render()

        assertEquals(
            "event=config.load_failed feature=\"rules\" revision=7 message=\"line one\\nline two\"",
            rendered,
        )
        assertTrue('\n' !in rendered)
    }

    @Test
    fun `record rendering escapes quotes and control whitespace`() {
        val rendered = DiagnosticRecord(
            level = DiagnosticLevel.Warning,
            event = DiagnosticEvent.RulesLoadFailed,
            message = "bad\tvalue\" forged=true\u0000",
        ).render()

        assertEquals(
            "event=rules.load_failed message=\"bad\\tvalue\\\" forged=true\\u0000\"",
            rendered,
        )
        assertTrue('\t' !in rendered)
    }
}
