package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RevisionSequenceTest {

    @Test
    fun advancesBeyondBothStoredAndPreviouslyIssuedRevision() {
        assertEquals(11L, RevisionSequence.next(storedRevision = 10L, lastIssuedRevision = 7L))
        assertEquals(12L, RevisionSequence.next(storedRevision = 4L, lastIssuedRevision = 11L))
    }

    @Test
    fun rejectsRevisionOverflow() {
        assertThrows(IllegalStateException::class.java) {
            RevisionSequence.next(Long.MAX_VALUE, 1L)
        }
    }
}
