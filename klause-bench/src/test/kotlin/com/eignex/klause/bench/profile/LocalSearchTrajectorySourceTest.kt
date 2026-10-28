package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalSearchTrajectorySourceTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `embedded sources retain their original hash pinned bytes`(compressed: Boolean) {
        val source = "<instance format=\"XCSP3\" type=\"CSP\"/>".encodeToByteArray()
        val payload = if (compressed) ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray() else source
        val encoded = Base64.getEncoder().encodeToString(payload)

        val bytes = trajectorySourceBytes(
            "xcsp3-csp", "control", sha256(source),
            encoded.takeUnless { compressed }, encoded.takeIf { compressed },
        )

        assertContentEquals(source, bytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `embedded sources with a different original hash decline`(compressed: Boolean) {
        val source = "<instance/>".encodeToByteArray()
        val payload = if (compressed) ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray() else source
        val encoded = Base64.getEncoder().encodeToString(payload)

        assertFailsWith<IllegalArgumentException> {
            trajectorySourceBytes(
                "xcsp3-csp", "control", "0".repeat(64),
                encoded.takeUnless { compressed }, encoded.takeIf { compressed },
            )
        }
    }

    @Test
    fun `oversized embedded sources decline before loading a catalog`() {
        assertFailsWith<IllegalArgumentException> {
            trajectorySourceBytes("unavailable", "control", "0".repeat(64), "A".repeat(96 * 1024 + 1))
        }
    }
}
