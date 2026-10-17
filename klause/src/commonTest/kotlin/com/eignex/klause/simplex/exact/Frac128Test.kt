package com.eignex.klause.simplex.exact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `additive identities should preserve normalized fixed-width values`() {
        val ops = Frac128Ops()
        val values = listOf(
            Frac128.ZERO,
            Frac128.ONE,
            Frac128.MINUS_ONE,
            Frac128(0L, 1L, 0L, 3L),
            Frac128(-1L, -1L, 0L, 3L),
            Frac128(1L, 1L, 0L, 3L),
            Frac128(0L, 1L, 1L, 1L),
            Frac128(-1L, -1L, 1L, 1L),
            Frac128(Long.MAX_VALUE, -1L, 0L, 1L),
            Frac128(Long.MIN_VALUE, 0L, 0L, 1L),
        )
        for (value in values) {
            val results = listOf(
                ops.plus(value, Frac128.ZERO),
                ops.minus(value, Frac128.ZERO),
                ops.plus(Frac128.ZERO, value),
            )

            for (result in results) {
                assertEquals(listOf(value.nHi, value.nLo, value.dHi, value.dLo),
                    listOf(result.nHi, result.nLo, result.dHi, result.dLo))
            }
            assertFalse(ops.overflowed())
        }
    }

    @Test
    fun `additive identities should preserve an existing overflow latch`() {
        val ops = Frac128Ops()
        val big = ops.ofLong(1L shl 62)
        ops.times(ops.times(big, big), big)

        val results = listOf(
            ops.plus(Frac128.ONE, Frac128.ZERO),
            ops.minus(Frac128.ONE, Frac128.ZERO),
            ops.plus(Frac128.ZERO, Frac128.ONE),
        )

        assertTrue(results.all { it.isZero })
        assertTrue(ops.overflowed())
    }

    @Test
    fun `subtracting the minimum numerator from zero should latch overflow`() {
        val ops = Frac128Ops()

        val result = ops.minus(Frac128.ZERO, Frac128(Long.MIN_VALUE, 0L, 0L, 1L))

        assertTrue(result.isZero)
        assertTrue(ops.overflowed())
    }

    @Test
    fun `fixed-width level should latch overflow past 128 bits`() {
        val ops = Frac128Ops()
        val big = ops.ofLong(1L shl 62)
        val sq = ops.times(big, big)
        assertTrue(!ops.overflowed())
        ops.times(sq, big)
        assertTrue(ops.overflowed())
    }
}
