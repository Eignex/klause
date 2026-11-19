package com.eignex.klause.simplex.exact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `equal wide denominators should combine without an overflowing denominator product`() {
        val ops = Frac128Ops()
        val first = Frac128(0L, 1L, 1L shl 36, 0L)
        val second = Frac128(0L, 3L, 1L shl 36, 0L)

        val sum = ops.plus(first, second)
        val difference = ops.minus(first, second)

        assertFalse(ops.overflowed())
        assertEquals(listOf(0L, 1L, 1L shl 34, 0L), listOf(sum.nHi, sum.nLo, sum.dHi, sum.dLo))
        assertEquals(listOf(-1L, -1L, 1L shl 35, 0L),
            listOf(difference.nHi, difference.nLo, difference.dHi, difference.dLo))
    }

    @Test
    fun `zero and unit operations should preserve wide rational signs`() {
        val value = Frac128(0L, 3L, 1L shl 36, 0L)
        for (negative in listOf(false, true)) {
            val ops = Frac128Ops()

            val product = ops.times(value, if (negative) ops.minusOne else ops.one)
            val result = ops.minus(ops.zero, product)

            assertFalse(ops.overflowed())
            assertEquals(if (negative) 3L else -3L, result.nLo)
            assertEquals(if (negative) 0L else -1L, result.nHi)
            assertEquals(value.dHi, result.dHi)
            assertEquals(value.dLo, result.dLo)
        }
    }

    @Test
    fun `negating the minimum fixed numerator should latch overflow`() {
        for (multiply in listOf(false, true)) {
            val ops = Frac128Ops()
            val value = Frac128(Long.MIN_VALUE, 0L, 0L, 1L)

            if (multiply) ops.times(ops.minusOne, value) else ops.minus(ops.zero, value)

            assertTrue(ops.overflowed())
            ops.plus(ops.zero, ops.one)
            assertTrue(ops.overflowed())
        }
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
