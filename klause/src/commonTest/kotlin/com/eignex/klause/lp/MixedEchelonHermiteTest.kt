package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The change of variables behind the mixed echelon-Hermite form. The property that carries everything is
 * that `x = V·y` is a bijection of the integer lattice: if it were not, a solution of the rewritten
 * system would not be a solution of the original, and an unsat proof would not transfer.
 */
class MixedEchelonHermiteTest {

    private fun vec(vararg v: Long) = Array(v.size) { BigInteger.fromLong(v[it]) }

    private fun dot(row: SparseIntRow, x: Array<BigInteger>): BigInteger {
        var acc = BigInteger.ZERO
        for (k in row.index.indices) acc += row.value[k] * x[row.index[k]]
        return acc
    }

    @Test
    fun `the transform is unimodular so the lattice is preserved`() {
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), emptyList(), 2)
        val v = r.transform
        val d = v[0, 0] * v[1, 1] - v[0, 1] * v[1, 0]
        assertTrue(d == BigInteger.ONE || d == BigInteger.ONE.negate(), "det V must be +-1, was $d")
    }

    @Test
    fun `an inequality keeps its value under the change of variables`() {
        // 2x0 + 3x1 = 0 drives the basis; the inequality row must evaluate identically at x = V*y.
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), sparseRows(longArrayOf(1, 1)), 2)
        val y = vec(3, -2)
        val x = r.recover(y)
        assertEquals(dot(sparseRows(longArrayOf(1, 1))[0], x), dot(r.inequalities[0], y), "row value is invariant")
    }

    @Test
    fun `the equality block becomes lower triangular`() {
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), emptyList(), 2)
        assertEquals(1, r.equalities.size)
        assertTrue(r.equalities[0][1].isZero(), "everything right of the pivot is cleared")
    }

    @Test
    fun `a dependent equality is dropped before the hermite step`() {
        // The second row is twice the first, so only one equality drives the basis.
        val r = mixedEchelonHermite(sparseRows(longArrayOf(1, 2), longArrayOf(2, 4)), emptyList(), 2)
        assertEquals(1, r.equalities.size)
    }

    @Test
    fun `a system with no equalities is left in its own variables`() {
        val r = mixedEchelonHermite(emptyList(), sparseRows(longArrayOf(5, 7)), 2)
        assertEquals(BigInteger.fromLong(5), r.inequalities[0][0])
        assertEquals(BigInteger.fromLong(7), r.inequalities[0][1])
        assertTrue(r.equalities.isEmpty())
    }

    @Test
    fun `the recovered point satisfies the original equality`() {
        // Any y with the transformed equality satisfied must map back onto 2x0 + 3x1 = 0.
        val original = sparseRows(longArrayOf(2, 3))
        val r = mixedEchelonHermite(original, emptyList(), 2)
        // The pivot is column 0, so y0 = 0 satisfies the transformed row for every y1.
        for (t in -3L..3L) {
            val x = r.recover(vec(0, t))
            assertEquals(BigInteger.ZERO, dot(original[0], x), "x = V*y must satisfy the original row")
        }
    }

    @Test
    fun `bounded rewritten variables bound the original ones`() {
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), emptyList(), 2)
        val b = r.originalBounds(arrayOf(BigInteger.ZERO, BigInteger.ZERO), arrayOf(BigInteger.ZERO, BigInteger.ZERO))
        // y pinned to the origin pins x to the origin, whatever V is.
        assertEquals(BigInteger.ZERO, b.lo[0])
        assertEquals(BigInteger.ZERO, b.hi[0])
    }

    @Test
    fun `an open rewritten variable leaves the original open in that direction`() {
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), emptyList(), 2)
        val b = r.originalBounds(arrayOf(null, null), arrayOf(null, null))
        assertTrue(b.lo.all { it == null }, "an unbounded y must not yield a bounded x")
        assertTrue(b.hi.all { it == null })
    }

    @Test
    fun `a derived bound never excludes a solution of the original system`() {
        // A bound derived through the transformation must contain every solution. Enumerated exhaustively
        // over a small box.
        val original = sparseRows(longArrayOf(2, -2)) // 2*x0 - 2*x1 = 0, i.e. x0 == x1
        val r = mixedEchelonHermite(original, emptyList(), 2)
        val span = 6L
        val yLo = arrayOf<BigInteger?>(BigInteger.fromLong(-span), BigInteger.fromLong(-span))
        val yHi = arrayOf<BigInteger?>(BigInteger.fromLong(span), BigInteger.fromLong(span))
        val b = r.originalBounds(yLo, yHi)
        for (y0 in -span..span) {
            for (y1 in -span..span) {
                val y = vec(y0, y1)
                if (!dot(r.equalities[0], y).isZero()) continue // not a solution of the rewritten row
                val x = r.recover(y)
                assertEquals(BigInteger.ZERO, dot(original[0], x), "recovered x must solve the original")
                for (i in x.indices) {
                    val lo = b.lo[i]
                    val hi = b.hi[i]
                    assertTrue(lo == null || x[i] >= lo, "x[$i]=${x[i]} fell below the derived lower bound $lo")
                    assertTrue(hi == null || x[i] <= hi, "x[$i]=${x[i]} rose above the derived upper bound $hi")
                }
            }
        }
    }
}
