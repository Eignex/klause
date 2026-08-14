package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fraction-free echelon reduction. The properties that matter are exactness — every intermediate is an
 * integer, never a rounded rational — and rank, since a dependent equality must not be mistaken for a
 * constraint the transformation has to carry.
 */
class BareissEchelonTest {

    @Suppress("ArrayPrimitive")
    private fun mat(vararg rows: LongArray): Array<Array<BigInteger>> =
        Array(rows.size) { i -> Array(rows[i].size) { j -> BigInteger.fromLong(rows[i][j]) } }

    @Test
    fun `an independent system keeps every row`() {
        val e = bareissEchelon(mat(longArrayOf(2, 1, 3), longArrayOf(1, 4, 1)))
        assertEquals(2, e.rows.size)
        assertEquals(listOf(0, 1), e.pivots.toList())
    }

    @Test
    fun `a dependent row reduces away and does not count toward the rank`() {
        // Row 2 is twice row 1, so the rank is 1.
        val e = bareissEchelon(mat(longArrayOf(1, 2, 3), longArrayOf(2, 4, 6)))
        assertEquals(1, e.rows.size, "a multiple of another row carries no constraint")
        assertEquals(listOf(0), e.pivots.toList())
    }

    @Test
    fun `entries below a pivot are cleared`() {
        val e = bareissEchelon(mat(longArrayOf(2, 1), longArrayOf(4, 5)))
        assertTrue(e.rows[1][0].isZero(), "the pivot column is clear below the pivot")
    }

    @Test
    fun `a zero leading column is skipped without consuming a row`() {
        val e = bareissEchelon(mat(longArrayOf(0, 3, 1), longArrayOf(0, 6, 5)))
        assertEquals(listOf(1), e.pivots.toList().take(1), "the first pivot is in column 1")
        assertEquals(2, e.rows.size)
    }

    @Test
    fun `elimination stays exact on entries that would carry denominators over the rationals`() {
        // Over Q this reduction produces thirds; fraction-free keeps every intermediate integral.
        val e = bareissEchelon(mat(longArrayOf(3, 1, 1), longArrayOf(1, 3, 1), longArrayOf(1, 1, 3)))
        assertEquals(3, e.rows.size)
        for (row in e.rows) for (v in row) assertTrue(v.toString().none { it == '.' || it == '/' })
    }

    @Test
    fun `a rank-deficient system reports only its independent rows`() {
        // r3 = r1 + r2, so the rank is 2.
        val e = bareissEchelon(mat(longArrayOf(1, 0, 2), longArrayOf(0, 1, 3), longArrayOf(1, 1, 5)))
        assertEquals(2, e.rows.size)
    }

    @Test
    fun `an empty system reduces to nothing`() {
        assertEquals(0, bareissEchelon(emptyArray()).rows.size)
    }

    @Test
    fun `intermediates stay bounded on a dense integer system`() {
        // Bareiss bounds entries by a minor of the input (Hadamard), unlike a naive fraction-free scheme
        // whose products compound; 6-digit inputs must not run to hundreds of digits.
        val n = 6
        val base = longArrayOf(100003, 99991, 100019, 99989, 100043, 99961)
        val m = Array(n) { i -> Array(n) { j -> BigInteger.fromLong(base[(i * 2 + j * 3) % n] + i + j) } }
        val e = bareissEchelon(m)
        var widest = 0
        for (row in e.rows) for (v in row) widest = maxOf(widest, v.abs().toString().length)
        assertTrue(widest < 60, "widest entry was $widest digits")
    }
}
