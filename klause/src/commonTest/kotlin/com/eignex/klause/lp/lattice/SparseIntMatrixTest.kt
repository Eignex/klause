package com.eignex.klause.lp.lattice

import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.lp.lattice.mixedEchelonHermite
import com.eignex.klause.lp.lattice.originalBounds
import com.eignex.klause.lp.lattice.sparseIntRow
import com.eignex.klause.lp.lattice.triangularBounds
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparseIntMatrixTest {

    private fun matrix(random: Random, rows: Int, cols: Int): List<SparseIntRow> = List(rows) {
        val entries = HashMap<Int, BigInt>()
        for (j in 0 until cols) {
            if (random.nextInt(cols) < 3) entries[j] = bigIntOf(random.nextInt(-4, 5))
        }
        sparseIntRow(entries)
    }

    private fun dense(a: List<SparseIntRow>, cols: Int): Array<Array<BigInt>> =
        Array(a.size) { i -> Array(cols) { j -> a[i][j] } }

    private fun densePivots(a: List<SparseIntRow>, cols: Int): List<Int> {
        val w = dense(a, cols)
        val m = w.size
        val pivots = ArrayList<Int>()
        var prev = BIG_ONE
        var r = 0
        for (c in 0 until cols) {
            if (r >= m) break
            val sel = (r until m).firstOrNull { !w[it][c].isZero() } ?: continue
            val t = w[sel]
            w[sel] = w[r]
            w[r] = t
            val pivot = w[r][c]
            for (i in r + 1 until m) {
                val factor = w[i][c]
                for (j in c until cols) w[i][j] = (pivot * w[i][j] - factor * w[r][j]) / prev
            }
            prev = pivot
            pivots.add(c)
            r++
        }
        return pivots
    }

    @Test
    fun `sparse elimination should match the dense rank and pivot columns`() {
        val random = Random(20260813)
        repeat(REPEATS) {
            val a = matrix(random, ROWS, COLS)
            val e = bareissEchelon(a, COLS)
            assertEquals(densePivots(a, COLS), e.pivots.toList(), "pivot columns must match on $a")
            assertEquals(e.pivots.size, e.rows.size)
        }
    }

    @Test
    fun `structural bounds should contain a witness of the original system`() {
        // The right-hand sides come from a witness, so the system provably has that solution.
        val random = Random(555)
        repeat(REPEATS) {
            val a = matrix(random, ROWS, COLS)
            val witness = Array(COLS) { bigIntOf(random.nextInt(-6, 7)) }
            val rhs = Array(a.size) { i ->
                var acc = BIG_ZERO
                for (k in a[i].index.indices) acc += a[i].value[k] * witness[a[i].index[k]]
                acc
            }
            val mixed = mixedEchelonHermite(a, emptyList(), COLS, rhs)
            if (mixed.equalities.isEmpty()) return@repeat
            val reduced = Array<BigInt?>(mixed.equalities.size) { mixed.equalityRhs[it] }
            val y = triangularBounds(mixed.equalities, COLS, reduced, reduced)
            val bounds = mixed.originalBounds(y.lo, y.hi)
            for (i in 0 until COLS) {
                val lo = bounds.lo[i]
                val hi = bounds.hi[i]
                assertTrue(lo == null || witness[i] >= lo, "x$i = ${witness[i]} fell below $lo")
                assertTrue(hi == null || witness[i] <= hi, "x$i = ${witness[i]} rose above $hi")
            }
        }
    }

    private companion object {
        const val ROWS = 5
        const val COLS = 6
        const val REPEATS = 20
    }
}
