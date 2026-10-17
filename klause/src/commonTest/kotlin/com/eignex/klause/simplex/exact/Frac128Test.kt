package com.eignex.klause.simplex.exact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `fixed-width products should preserve carries and signs across words`() {
        for ((left, right, expected) in listOf(
            Triple(
                Frac128(1L, 1L, 0L, 1L),
                Frac128(0L, 3L, 0L, 1L),
                Frac128(3L, 3L, 0L, 1L),
            ),
            Triple(
                Frac128(0L, Long.MAX_VALUE, 0L, 1L),
                Frac128(0L, Long.MAX_VALUE, 0L, 1L),
                Frac128(Long.MAX_VALUE ushr 1, 1L, 0L, 1L),
            ),
            Triple(
                Frac128(-1L, -Long.MAX_VALUE, 0L, 1L),
                Frac128(0L, Long.MAX_VALUE, 0L, 1L),
                Frac128(-(1L shl 62), -1L, 0L, 1L),
            ),
            Triple(
                Frac128(0L, 1L, 1L, 1L),
                Frac128(0L, 1L, 0L, 3L),
                Frac128(0L, 1L, 3L, 3L),
            ),
            Triple(
                Frac128(-1L, -1L, 1L, 1L),
                Frac128(0L, 3L, 0L, 1L),
                Frac128(-1L, -3L, 1L, 1L),
            ),
            Triple(
                Frac128(1L, 1L, 0L, 3L),
                Frac128(0L, 3L, 1L, 1L),
                Frac128(0L, 1L, 0L, 1L),
            ),
            Triple(
                Frac128(Long.MIN_VALUE, 0L, 0L, 1L),
                Frac128.ONE,
                Frac128(Long.MIN_VALUE, 0L, 0L, 1L),
            ),
        )) {
            for ((a, b) in listOf(left to right, right to left)) {
                val ops = Frac128Ops()

                val result = ops.times(a, b)

                assertTrue(!ops.overflowed())
                assertEquals(expected.nHi, result.nHi)
                assertEquals(expected.nLo, result.nLo)
                assertEquals(expected.dHi, result.dHi)
                assertEquals(expected.dLo, result.dLo)
            }
        }
    }

    @Test
    fun `fixed-width positive products should latch at the signed numerator limit`() {
        val ops = Frac128Ops()
        val positive = Frac128(1L shl 62, 0L, 0L, 1L)

        ops.times(positive, ops.ofLong(2L))

        assertTrue(ops.overflowed())
    }

    @Test
    fun `fixed-width level should latch overflow past 128 bits`() {
        for (highWord in listOf(false, true)) {
            val ops = Frac128Ops()
            val big = ops.ofLong(1L shl 62)
            val sq = ops.times(big, big)
            assertTrue(!ops.overflowed())

            ops.times(sq, if (highWord) Frac128(1L, 0L, 0L, 1L) else big)

            assertTrue(ops.overflowed())
        }
    }
}
