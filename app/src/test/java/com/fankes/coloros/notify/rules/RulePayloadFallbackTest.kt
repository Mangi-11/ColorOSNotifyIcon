package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RulePayloadFallbackTest {

    @Test
    fun selectsPreviousWhenCurrentReadOrValidationFails() {
        val selection = RulePayloadFallback.load(
            candidates = listOf(
                RulePayloadFallback.Candidate("current", "1") { throw IOException("corrupt") },
                RulePayloadFallback.Candidate("previous", "2") { "previous-json" },
            ),
            validate = { json, _ -> json.length },
        )

        assertEquals("previous", selection.candidate.fileName)
        assertEquals("previous-json".length, selection.value)
        assertEquals("corrupt", selection.failures.single().message)
    }

    @Test
    fun throwsFirstFailureAndAttachesLaterFailures() {
        val exception = assertThrows(IOException::class.java) {
            RulePayloadFallback.load(
                candidates = listOf(
                    RulePayloadFallback.Candidate("current", "1") { throw IOException("current") },
                    RulePayloadFallback.Candidate("previous", "2") { throw IOException("previous") },
                ),
                validate = { _, _ -> Unit },
            )
        }

        assertEquals("current", exception.message)
        assertEquals("previous", exception.suppressed.single().message)
    }

    @Test
    fun doesNotEvaluateOrReportInvalidPreviousWhenCurrentIsValid() {
        val selection = RulePayloadFallback.load(
            candidates = listOf(
                RulePayloadFallback.Candidate("current", "1") { "current-json" },
                RulePayloadFallback.Candidate("invalid-previous", "") {
                    throw IOException("previous pointer invalid")
                },
            ),
            validate = { json, _ -> json },
        )

        assertEquals("current", selection.candidate.fileName)
        assertEquals(emptyList<Exception>(), selection.failures)
    }
}
