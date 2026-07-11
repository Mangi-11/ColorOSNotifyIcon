package com.fankes.coloros.notify.rules

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object RulePayloadIO {
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun readBytes(
        input: InputStream,
        maxBytes: Int = MAX_PAYLOAD_BYTES,
    ): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Rule payload exceeds $maxBytes bytes" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun readUtf8(
        input: InputStream,
        maxBytes: Int = MAX_PAYLOAD_BYTES,
    ): String = readBytes(input, maxBytes).toString(Charsets.UTF_8)

    private const val INITIAL_BUFFER_BYTES = 64 * 1024
    private const val BUFFER_BYTES = 16 * 1024
}
