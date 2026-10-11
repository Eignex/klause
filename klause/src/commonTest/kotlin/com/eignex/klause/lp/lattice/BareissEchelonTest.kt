package com.eignex.klause.lp.lattice

import com.eignex.klause.lp.lattice.BareissEchelon
import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.lp.lattice.sparseIntRow
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fraction-free echelon reduction. The properties that matter are exactness — every intermediate is an
 * integer, never a rounded rational — and rank, since a dependent equality must not be mistaken for a
 * constraint the transformation has to carry.
 */
class BareissEchelonTest {

    private fun echelon(vararg rows: LongArray): BareissEchelon =
        bareissEchelon(sparseRows(*rows), rows.maxOf { it.size })

    @Test
    fun `a dependent row reduces away and does not count toward the rank`() {
        // Row 2 is twice row 1, so the rank is 1.
        val e = echelon(longArrayOf(1, 2, 3), longArrayOf(2, 4, 6))
        assertEquals(1, e.rows.size, "a multiple of another row carries no constraint")
        assertEquals(listOf(0), e.pivots.toList())
    }

    @Test
    fun `a zero leading column is skipped without consuming a row`() {
        val e = echelon(longArrayOf(0, 3, 1), longArrayOf(0, 6, 5))
        assertEquals(listOf(1), e.pivots.toList().take(1), "the first pivot is in column 1")
        assertEquals(2, e.rows.size)
    }

    @Test
    fun `elimination stays exact on entries that would carry denominators over the rationals`() {
        // Over Q this reduction produces thirds; fraction-free keeps every intermediate integral.
        val e = echelon(longArrayOf(3, 1, 1), longArrayOf(1, 3, 1), longArrayOf(1, 1, 3))
        assertEquals(3, e.rows.size)
        for (row in e.rows) for (v in row.value) assertTrue(v.toString().none { it == '.' || it == '/' })
    }

    @Test
    fun `an empty system reduces to nothing`() {
        assertEquals(0, bareissEchelon(emptyList(), 0).rows.size)
    }

    @Test
    fun `intermediates stay bounded on a dense integer system`() {
        // Stripping the row content after each step bounds the entries far below what a naive
        // fraction-free scheme's compounding products reach; 6-digit inputs must not run to hundreds of
        // digits.
        val n = 6
        val base = longArrayOf(100003, 99991, 100019, 99989, 100043, 99961)
        val m = List(n) { i ->
            sparseIntRow((0 until n).associateWith { j -> bigIntOf(base[(i * 2 + j * 3) % n] + i + j) })
        }
        val e = bareissEchelon(m, n)
        var widest = 0
        for (row in e.rows) for (v in row.value) widest = maxOf(widest, v.abs().toString().length)
        assertTrue(widest < 60, "widest entry was $widest digits")
    }
}
