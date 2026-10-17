package com.eignex.klause.simplex.exact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `fixed-width sums should preserve carries signs and rational reduction`() {
        val cases = listOf(
            Triple(Frac128(0, -1, 0, 1), Frac128.ONE, Frac128(1, 0, 0, 1)),
            Triple(Frac128(-1, 1, 0, 1), Frac128.MINUS_ONE, Frac128(-1, 0, 0, 1)),
            Triple(Frac128(1, 0, 0, 1), Frac128.MINUS_ONE, Frac128(0, -1, 0, 1)),
            Triple(Frac128(0, 1, 0, 3), Frac128(0, 1, 0, 6), Frac128(0, 1, 0, 2)),
            Triple(Frac128(0, 1, 0, 3), Frac128(-1, -1, 0, 6), Frac128(0, 1, 0, 6)),
            Triple(Frac128(-1, -1, 0, 3), Frac128(0, 1, 0, 6), Frac128(-1, -1, 0, 6)),
            Triple(Frac128(-1, -1, 0, 3), Frac128(-1, -1, 0, 6), Frac128(-1, -1, 0, 2)),
            Triple(Frac128(-1, -1, 0, 3), Frac128(0, 1, 0, 3), Frac128.ZERO),
            Triple(Frac128(1, 0, 0, 3), Frac128(1, 0, 0, 3), Frac128(2, 0, 0, 3)),
        )
        for ((first, second, expected) in cases) {
            for ((a, b) in listOf(first to second, second to first)) {
                val ops = Frac128Ops()

                val result = ops.plus(a, b)

                assertTrue(!ops.overflowed())
                assertEquals(listOf(expected.nHi, expected.nLo, expected.dHi, expected.dLo),
                    listOf(result.nHi, result.nLo, result.dHi, result.dLo))
            }
        }
    }

    @Test
    fun `fixed-width differences should preserve borrows and signed magnitude order`() {
        val cases = listOf(
            Triple(Frac128(1, 0, 0, 1), Frac128.ONE, Frac128(0, -1, 0, 1)),
            Triple(Frac128(-1, 0, 0, 1), Frac128.MINUS_ONE, Frac128(-1, 1, 0, 1)),
            Triple(Frac128(0, 1, 0, 3), Frac128(0, 1, 0, 6), Frac128(0, 1, 0, 6)),
            Triple(Frac128(0, 1, 0, 6), Frac128(0, 1, 0, 3), Frac128(-1, -1, 0, 6)),
            Triple(Frac128(-1, -1, 0, 3), Frac128(0, 1, 0, 6), Frac128(-1, -1, 0, 2)),
            Triple(Frac128(-1, -1, 0, 6), Frac128(-1, -1, 0, 3), Frac128(0, 1, 0, 6)),
            Triple(Frac128(1, 0, 0, 3), Frac128(1, 0, 0, 3), Frac128.ZERO),
        )
        for ((a, b, expected) in cases) {
            val ops = Frac128Ops()

            val result = ops.minus(a, b)

            assertTrue(!ops.overflowed())
            assertEquals(listOf(expected.nHi, expected.nLo, expected.dHi, expected.dLo),
                listOf(result.nHi, result.nLo, result.dHi, result.dLo))
        }
    }

    @Test
    fun `repeated fixed-width operands should retain their values across arithmetic calls`() {
        val ops = Frac128Ops()
        val a = Frac128(0, 1, 0, 3)
        val b = Frac128(0, 1, 0, 6)
        val first = ops.plus(a, b)

        val doubled = ops.plus(a, a)
        val cancelled = ops.minus(a, a)
        val difference = ops.minus(a, b)
        val order = ops.compare(a, b)

        assertTrue(!ops.overflowed())
        assertEquals(listOf(0L, 1L, 0L, 3L), listOf(a.nHi, a.nLo, a.dHi, a.dLo))
        assertEquals(listOf(0L, 1L, 0L, 6L), listOf(b.nHi, b.nLo, b.dHi, b.dLo))
        assertEquals(listOf(0L, 1L, 0L, 2L), listOf(first.nHi, first.nLo, first.dHi, first.dLo))
        assertEquals(listOf(0L, 2L, 0L, 3L), listOf(doubled.nHi, doubled.nLo, doubled.dHi, doubled.dLo))
        assertEquals(listOf(0L, 0L, 0L, 1L), listOf(cancelled.nHi, cancelled.nLo, cancelled.dHi, cancelled.dLo))
        assertEquals(listOf(0L, 1L, 0L, 6L), listOf(difference.nHi, difference.nLo, difference.dHi, difference.dLo))
        assertTrue(order > 0)
    }

    @Test
    fun `fixed-width addition should admit the negative signed boundary`() {
        val ops = Frac128Ops()
        val operand = Frac128(-(1L shl 62), 0, 0, 1)

        val result = ops.plus(operand, operand)

        assertTrue(!ops.overflowed())
        assertEquals(listOf(Long.MIN_VALUE, 0L, 0L, 1L),
            listOf(result.nHi, result.nLo, result.dHi, result.dLo))
    }

    @Test
    fun `fixed-width addition should latch beyond the positive signed boundary`() {
        val ops = Frac128Ops()
        val operand = Frac128(1L shl 62, 0, 0, 1)

        val result = ops.plus(operand, operand)

        assertSame(Frac128.ZERO, result)
        assertTrue(ops.overflowed())
    }

    @Test
    fun `fixed-width addition should latch an unsigned magnitude carry`() {
        val ops = Frac128Ops()
        val operand = Frac128(Long.MIN_VALUE, 0, 0, 1)

        val result = ops.plus(operand, operand)

        assertSame(Frac128.ZERO, result)
        assertTrue(ops.overflowed())
    }

    @Test
    fun `fixed-width sums should retain intermediate product refusal and sticky overflow`() {
        val large = Frac128(1L shl 60, 0, 0, 1)
        val small = Frac128(0, 1, 1L shl 60, 0)
        for ((a, b) in listOf(large to small, small to large, small to small)) {
            for (subtract in listOf(false, true)) {
                val ops = Frac128Ops()

                val result = if (subtract) ops.minus(a, b) else ops.plus(a, b)

                assertSame(Frac128.ZERO, result)
                assertTrue(ops.overflowed())
                assertSame(Frac128.ZERO, ops.plus(Frac128.ONE, Frac128.ONE))
                assertTrue(ops.overflowed())
            }
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
