package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class LocalSearchTrajectorySourceTest {
    @Test
    fun `embedded sources retain their hash pinned bytes`() {
        val source = "<instance format=\"XCSP3\" type=\"CSP\"/>".encodeToByteArray()

        val bytes = trajectorySourceBytes("xcsp3-csp", "control", sha256(source),
            Base64.getEncoder().encodeToString(source))

        assertContentEquals(source, bytes)
    }

    @Test
    fun `compressed embedded sources retain their original bytes`() {
        val source = "<instance format=\"XCSP3\" type=\"CSP\"/>".encodeToByteArray()
        val packed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray()

        val bytes = trajectorySourceBytes(
            "xcsp3-csp", "control", sha256(source), null, Base64.getEncoder().encodeToString(packed),
        )

        assertContentEquals(source, bytes)
    }

    @Test
    fun `compressed embedded sources still require the original hash`() {
        val source = "<instance/>".encodeToByteArray()
        val packed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray()

        assertFailsWith<IllegalArgumentException> {
            trajectorySourceBytes(
                "xcsp3-csp", "control", "0".repeat(64), null, Base64.getEncoder().encodeToString(packed),
            )
        }
    }

    @Test
    fun `embedded sources with a different original hash decline`() {
        val source = "<instance/>".encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            trajectorySourceBytes("xcsp3-csp", "control", "0".repeat(64),
                Base64.getEncoder().encodeToString(source))
        }
    }

    @Test
    fun `oversized embedded sources decline before loading a catalog`() {
        assertFailsWith<IllegalArgumentException> {
            trajectorySourceBytes("unavailable", "control", "0".repeat(64), "A".repeat(96 * 1024 + 1))
        }
    }
}
