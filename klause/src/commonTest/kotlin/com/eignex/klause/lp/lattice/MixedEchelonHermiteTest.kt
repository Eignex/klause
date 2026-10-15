package com.eignex.klause.lp.lattice

import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.mixedEchelonHermite
import com.eignex.klause.lp.lattice.originalBounds
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedEchelonHermiteTest {

    private fun vec(vararg v: Long) = Array(v.size) { bigIntOf(v[it]) }

    private fun dot(row: SparseIntRow, x: Array<BigInt>): BigInt {
        var acc = BIG_ZERO
        for (k in row.index.indices) acc += row.value[k] * x[row.index[k]]
        return acc
    }

    @Test
    fun `reduction should use a unimodular transform`() {
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), emptyList(), 2)
        val v = r.transform
        val d = v[0, 0] * v[1, 1] - v[0, 1] * v[1, 0]
        assertTrue(d == BIG_ONE || d == BIG_ONE.negate(), "det V must be +-1, was $d")
    }

    @Test
    fun `reduction should preserve inequality values under the change of variables`() {
        // 2x0 + 3x1 = 0 drives the basis; the inequality row must evaluate identically at x = V*y.
        val r = mixedEchelonHermite(sparseRows(longArrayOf(2, 3)), sparseRows(longArrayOf(1, 1)), 2)
        val y = vec(3, -2)
        val x = r.recover(y)
        assertEquals(dot(sparseRows(longArrayOf(1, 1))[0], x), dot(r.inequalities[0], y))
    }

    @Test
    fun `reduction should drop a dependent equality before the hermite step`() {
        // The second row is twice the first, so only one equality drives the basis.
        val r = mixedEchelonHermite(sparseRows(longArrayOf(1, 2), longArrayOf(2, 4)), emptyList(), 2)
        assertEquals(1, r.equalities.size)
    }

    @Test
    fun `reduction should leave a system without equalities in its own variables`() {
        val r = mixedEchelonHermite(emptyList(), sparseRows(longArrayOf(5, 7)), 2)
        assertEquals(bigIntOf(5), r.inequalities[0][0])
        assertEquals(bigIntOf(7), r.inequalities[0][1])
        assertTrue(r.equalities.isEmpty())
    }

    @Test
    fun `derived bounds should never exclude a solution of the original system`() {
        val original = sparseRows(longArrayOf(2, -2)) // 2*x0 - 2*x1 = 0, i.e. x0 == x1
        val r = mixedEchelonHermite(original, emptyList(), 2)
        val span = 6L
        val yLo = arrayOf<BigInt?>(bigIntOf(-span), bigIntOf(-span))
        val yHi = arrayOf<BigInt?>(bigIntOf(span), bigIntOf(span))
        val b = r.originalBounds(yLo, yHi)
        for (y0 in -span..span) {
            for (y1 in -span..span) {
                val y = vec(y0, y1)
                if (!dot(r.equalities[0], y).isZero()) continue // not a solution of the rewritten row
                val x = r.recover(y)
                assertEquals(BIG_ZERO, dot(original[0], x))
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
