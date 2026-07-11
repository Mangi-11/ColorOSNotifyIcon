package com.fankes.coloros.notify.rules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class RulePayloadIOTest {

    @Test
    fun readsPayloadWithinLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            bytes,
            RulePayloadIO.readBytes(ByteArrayInputStream(bytes), maxBytes = bytes.size),
        )
    }

    @Test
    fun rejectsPayloadBeyondLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RulePayloadIO.readBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                maxBytes = 4,
            )
        }
    }
}
