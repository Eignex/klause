package com.eignex.klause.bench.profile

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class XcspArchiveReplayTest {
    @Test
    fun `an oversized compressed request is rejected before decoding`() {
        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode("a".repeat(XcspArchiveReplay.MAX_COMPRESSED_BYTES * 2))
        }
    }

    @Test
    fun `an expanded request cannot exceed the decompression allowance`() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(XcspArchiveReplay.MAX_REQUEST_BYTES + 1)) }

        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode(Base64.getEncoder().encodeToString(output.toByteArray()))
        }
    }

    @Test
    fun `duplicate request keys cannot change replay admission`() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write("{\"archives\":[],\"archives\":[{}]}".toByteArray()) }

        assertFailsWith<IllegalArgumentException> {
            XcspArchiveReplay.decode(Base64.getEncoder().encodeToString(output.toByteArray()))
        }
    }
}
