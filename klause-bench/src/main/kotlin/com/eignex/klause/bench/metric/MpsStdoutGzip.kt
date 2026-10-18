package com.eignex.klause.bench.metric

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

internal object MpsStdoutGzip {
    const val MAX_WIRE_BYTES: Int = 8 * 1024 * 1024
    const val MAX_DECODED_BYTES: Int = 64 * 1024 * 1024
    private const val BLOCK_BYTES = 64 * 1024

    fun encode(raw: ByteArray, cancelled: () -> Boolean = { false }): ByteArray {
        check(raw.size <= MAX_DECODED_BYTES) { "decoded stdout exceeds compressed transport limit" }
        val output = Payload()
        GZIPOutputStream(output).use { gzip ->
            var offset = 0
            while (offset < raw.size) {
                check(!cancelled()) { "stdout compression allowance exhausted" }
                val count = minOf(BLOCK_BYTES, raw.size - offset)
                gzip.write(raw, offset, count)
                offset += count
            }
            check(!cancelled()) { "stdout compression allowance exhausted" }
        }
        return output.bytes()
    }

    private class Payload : OutputStream() {
        private val output = ByteArrayOutputStream()

        override fun write(value: Int) {
            check(output.size() < MAX_WIRE_BYTES) { "compressed stdout exceeds checker limit" }
            output.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(output.size().toLong() + length <= MAX_WIRE_BYTES) {
                "compressed stdout exceeds checker limit"
            }
            output.write(bytes, offset, length)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
