package com.eignex.klause.solver.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** #18: overflow-checked Long arithmetic underpinning the fraction-free simplex tableau. */
class CheckedLongTest {

    @Test
    fun `add sub mul match plain arithmetic in range`() {
        assertEquals(7L, addExact(3L, 4L))
        assertEquals(-1L, subExact(3L, 4L))
        assertEquals(12L, mulExact(3L, 4L))
        assertEquals(0L, mulExact(0L, Long.MAX_VALUE))
    }

    @Test
    fun `addExact detects overflow`() {
        assertFailsWith<LpOverflowException> { addExact(Long.MAX_VALUE, 1L) }
        assertFailsWith<LpOverflowException> { addExact(Long.MIN_VALUE, -1L) }
    }

    @Test
    fun `subExact detects overflow`() {
        assertFailsWith<LpOverflowException> { subExact(Long.MIN_VALUE, 1L) }
        assertFailsWith<LpOverflowException> { subExact(Long.MAX_VALUE, -1L) }
    }

    @Test
    fun `mulExact detects overflow`() {
        assertFailsWith<LpOverflowException> { mulExact(Long.MAX_VALUE, 2L) }
        assertFailsWith<LpOverflowException> { mulExact(Long.MIN_VALUE, -1L) }
        assertFailsWith<LpOverflowException> { mulExact(3_037_000_500L, 3_037_000_500L) }
    }

    @Test
    fun `gcd basics`() {
        assertEquals(6L, gcdLong(12L, 18L))
        assertEquals(7L, gcdLong(7L, 0L))
        assertEquals(7L, gcdLong(0L, 7L))
        assertEquals(4L, gcdLong(-12L, 8L))
        assertEquals(0L, gcdLong(0L, 0L))
    }
}
