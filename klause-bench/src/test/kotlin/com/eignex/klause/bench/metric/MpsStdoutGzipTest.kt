package com.eignex.klause.bench.metric

import java.util.Random
import java.util.zip.GZIPInputStream
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpsStdoutGzipTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 63, 64, 65535, 65536, 65537])
    fun `transport should retain every original byte`(size: Int) {
        val raw = ByteArray(size) { (it * 31).toByte() }

        val encoded = MpsStdoutGzip.encode(raw)
        val decoded = GZIPInputStream(encoded.inputStream()).use { it.readBytes() }

        assertContentEquals(raw, decoded)
    }

    @Test
    fun `repeated incumbent history should fit bounded wire transport`() {
        val raw = ("o 1\nv x=1\n".repeat(900_000)).toByteArray()

        val encoded = MpsStdoutGzip.encode(raw)
        val decoded = GZIPInputStream(encoded.inputStream()).use { it.readBytes() }

        assertTrue(raw.size > MpsStdoutGzip.MAX_WIRE_BYTES)
        assertTrue(encoded.size <= MpsStdoutGzip.MAX_WIRE_BYTES)
        assertContentEquals(raw, decoded)
    }

    @Test
    fun `compression should stop when its containing allowance ends`() {
        val raw = ByteArray(128 * 1024)
        var polls = 0

        assertFailsWith<IllegalStateException> {
            MpsStdoutGzip.encode(raw) { ++polls == 2 }
        }
    }

    @Test
    fun `incompressible stdout should retain the wire size limit`() {
        val raw = ByteArray(MpsStdoutGzip.MAX_WIRE_BYTES + 65536)
        Random(1).nextBytes(raw)

        assertFailsWith<IllegalStateException> {
            MpsStdoutGzip.encode(raw)
        }
    }

    @Test
    fun `stdout beyond the decoded limit should be declined`() {
        val raw = ByteArray(MpsStdoutGzip.MAX_DECODED_BYTES + 1)

        assertFailsWith<IllegalStateException> {
            MpsStdoutGzip.encode(raw)
        }
    }
}
