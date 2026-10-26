package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.util.Base64
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
            trajectorySourceBytes("unavailable", "control", "0".repeat(64), "A".repeat(88 * 1024 + 1))
        }
    }
}
