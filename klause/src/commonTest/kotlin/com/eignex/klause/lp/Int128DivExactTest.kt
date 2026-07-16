package com.eignex.klause.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Int128.divExactByLong] — the exact division the fraction-free (Bareiss) basis solve relies on. */
class Int128DivExactTest {

    private fun product(a: Long, b: Long): Int128 = Int128().apply { addProduct(a, b) }

    @Test
    fun `divides a 128-bit product exactly`() {
        assertEquals(1_000_000_000_000L, product(1_000_000_000L, 1_000_000_000L).divExactByLong(1_000_000L))
        assertEquals(7L, product(7L, 11L).divExactByLong(11L))
    }

    @Test
    fun `returns null on a nonzero remainder`() {
        assertNull(product(7L, 11L).divExactByLong(10L))
    }

    @Test
    fun `handles negative operands with sign-correct exact quotients`() {
        assertEquals(-6L, product(-6L, 5L).divExactByLong(5L))
        assertEquals(10L, product(-6L, 5L).divExactByLong(-3L))
        assertEquals(6L, product(-6L, -5L).divExactByLong(5L))
    }

    @Test
    fun `returns null when the exact quotient does not fit a Long`() {
        // 4·(2⁶³−1) is a 65-bit magnitude; dividing by 1 cannot land in a Long.
        assertNull(product(Long.MAX_VALUE, 4L).divExactByLong(1L))
    }

    @Test
    fun `returns null for a zero divisor`() {
        assertNull(product(3L, 5L).divExactByLong(0L))
    }

    @Test
    fun `divides down to the signed Long boundary`() {
        // −2⁶³ = (2⁶²)·(−2): the exact quotient is Long.MIN_VALUE, which does fit.
        assertEquals(Long.MIN_VALUE, product(1L shl 62, -2L).divExactByLong(1L))
    }
}
