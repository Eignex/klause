package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bounding a lower-triangular system by forward substitution. The cases that matter are the ones where a
 * side stays open: a bound invented where the system implies none would defeat the whole point of the
 * transformation, which is to replace an invented search box with the model's own bounds.
 */
class TriangularBoundsTest {

    private fun big(v: Long) = BigInteger.fromLong(v)

    @Suppress("ArrayPrimitive")
    private fun mat(vararg rows: LongArray): Array<Array<BigInteger>> =
        Array(rows.size) { i -> Array(rows[i].size) { j -> big(rows[i][j]) } }

    @Suppress("ArrayPrimitive")
    private fun sides(vararg v: Long?): Array<BigInteger?> = Array(v.size) { i -> v[i]?.let { big(it) } }

    @Test
    fun `a diagonal row bounds its own column`() {
        val b = triangularBounds(mat(longArrayOf(1)), sides(-3), sides(7))
        assertEquals(big(-3), b.lo[0])
        assertEquals(big(7), b.hi[0])
    }

    @Test
    fun `a later row bounds its pivot from the column already bounded`() {
        // y0 in [0, 10]; y0 + y1 in [0, 12]  =>  y1 <= 12 - 0 = 12 and y1 >= 0 - 10 = -10.
        val h = mat(longArrayOf(1, 0), longArrayOf(1, 1))
        val b = triangularBounds(h, sides(0, 0), sides(10, 12))
        assertEquals(big(-10), b.lo[1])
        assertEquals(big(12), b.hi[1])
    }

    @Test
    fun `a negative pivot exchanges the two sides`() {
        // -2·y0 in [-4, 6]  =>  y0 in [ceil(6 / -2), floor(-4 / -2)] = [-3, 2].
        val b = triangularBounds(mat(longArrayOf(-2)), sides(-4), sides(6))
        assertEquals(big(-3), b.lo[0])
        assertEquals(big(2), b.hi[0])
    }

    @Test
    fun `division rounds inward so the bound admits no extra integer`() {
        // 3·y0 <= 7  =>  y0 <= 2, not 2.33; 3·y0 >= -7  =>  y0 >= -2.
        val b = triangularBounds(mat(longArrayOf(3)), sides(-7), sides(7))
        assertEquals(big(-2), b.lo[0])
        assertEquals(big(2), b.hi[0])
    }

    @Test
    fun `an unbounded row side leaves that direction open`() {
        val b = triangularBounds(mat(longArrayOf(1)), sides(null), sides(5))
        assertNull(b.lo[0], "no lower bound is implied")
        assertEquals(big(5), b.hi[0])
    }

    @Test
    fun `an open earlier column leaves the pivot open on the side it feeds`() {
        // y0 is open above; y0 + y1 <= 12 then implies no lower bound for y1.
        val h = mat(longArrayOf(1, 0), longArrayOf(1, 1))
        val b = triangularBounds(h, sides(0, 0), sides(null, 12))
        assertNull(b.hi[0])
        assertNull(b.lo[1], "an open rest-term cannot bound the pivot below")
        assertEquals(big(12), b.hi[1])
    }

    @Test
    fun `a column pivoting in no row stays open`() {
        val b = triangularBounds(mat(longArrayOf(1, 0)), sides(0), sides(4))
        assertEquals(big(4), b.hi[0])
        assertNull(b.lo[1], "column 1 is in no row's pivot position")
        assertNull(b.hi[1])
    }

    @Test
    fun `bounds past Long are derived exactly`() {
        // 8·y0 <= 2^70, whose bound no Long domain could hold.
        val huge = BigInteger.fromLong(2).pow(70)
        val b = triangularBounds(mat(longArrayOf(8)), sides(0), arrayOf(huge))
        assertEquals(BigInteger.fromLong(2).pow(67), b.hi[0])
    }

    @Test
    fun `a zero row bounds nothing`() {
        val b = triangularBounds(mat(longArrayOf(0, 0)), sides(-1), sides(1))
        assertNull(b.lo[0])
        assertNull(b.hi[0])
    }
}
