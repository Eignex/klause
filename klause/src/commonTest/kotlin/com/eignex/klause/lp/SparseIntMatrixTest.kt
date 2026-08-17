package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sparse echelon-Hermite chain against a dense reduction of the same matrices.
 *
 * The chain derives bounds that PRUNE search, so a transform that is not unimodular or a triangle paired
 * with the wrong right-hand side does not crash — it returns a wrong verdict. Each property is therefore
 * checked over randomly generated systems rather than on hand-picked shapes: the rank and the pivot
 * columns must agree with the dense reduction, `H = A·V` must hold with `V` unimodular, and a witness of
 * the original system must lie inside every bound the chain derives for it.
 */
class SparseIntMatrixTest {

    private fun matrix(random: Random, rows: Int, cols: Int): List<SparseIntRow> = List(rows) {
        val entries = HashMap<Int, BigInteger>()
        for (j in 0 until cols) {
            if (random.nextInt(cols) < 3) entries[j] = BigInteger.fromInt(random.nextInt(-4, 5))
        }
        sparseIntRow(entries)
    }

    private fun dense(a: List<SparseIntRow>, cols: Int): Array<Array<BigInteger>> =
        Array(a.size) { i -> Array(cols) { j -> a[i][j] } }

    /** The reduction the sparse chain replaces: dense fraction-free elimination, pivot columns only. */
    private fun densePivots(a: List<SparseIntRow>, cols: Int): List<Int> {
        val w = dense(a, cols)
        val m = w.size
        val pivots = ArrayList<Int>()
        var prev = BigInteger.ONE
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

    /** `det` by fraction-free elimination, only ever asked whether the answer is `±1`. */
    private fun determinant(m: Array<Array<BigInteger>>): BigInteger {
        val n = m.size
        val a = Array(n) { i -> Array(n) { j -> m[i][j] } }
        var sign = 1
        var prev = BigInteger.ONE
        for (k in 0 until n - 1) {
            if (a[k][k].isZero()) {
                val swap = (k + 1 until n).firstOrNull { !a[it][k].isZero() } ?: return BigInteger.ZERO
                val t = a[k]
                a[k] = a[swap]
                a[swap] = t
                sign = -sign
            }
            for (i in k + 1 until n) {
                for (j in k + 1 until n) a[i][j] = (a[i][j] * a[k][k] - a[i][k] * a[k][j]) / prev
            }
            prev = a[k][k]
        }
        val d = a[n - 1][n - 1]
        return if (sign < 0) -d else d
    }

    @Test
    fun `the sparse elimination finds the rank and pivot columns the dense reduction finds`() {
        val random = Random(20260813)
        repeat(REPEATS) {
            val a = matrix(random, ROWS, COLS)
            val e = bareissEchelon(a, COLS)
            assertEquals(densePivots(a, COLS), e.pivots.toList(), "pivot columns must match on $a")
            assertEquals(e.pivots.size, e.rows.size, "one reduced row per pivot")
        }
    }

    @Test
    fun `every reduced row leads at its own pivot column`() {
        val random = Random(31)
        repeat(REPEATS) {
            val e = bareissEchelon(matrix(random, ROWS, COLS), COLS)
            for (i in e.rows.indices) assertEquals(e.pivots[i], e.rows[i].lead)
        }
    }

    @Test
    fun `the hermite factorisation reproduces the reduced matrix exactly`() {
        val random = Random(77)
        repeat(REPEATS) {
            val a = bareissEchelon(matrix(random, ROWS, COLS), COLS).rows
            if (a.isEmpty()) return@repeat
            val f = hermiteNormalForm(a, COLS)
            assertNotNull(f)
            for (i in a.indices) {
                for (j in 0 until COLS) {
                    var acc = BigInteger.ZERO
                    for (k in 0 until COLS) acc += a[i][k] * f.v[k, j]
                    assertEquals(acc, f.h[i][j], "H must equal A*V at row $i column $j")
                }
            }
        }
    }

    @Test
    fun `the hermite transform is unimodular so the integer lattice is preserved`() {
        val random = Random(99)
        repeat(REPEATS) {
            val a = bareissEchelon(matrix(random, ROWS, COLS), COLS).rows
            if (a.isEmpty()) return@repeat
            val f = hermiteNormalForm(a, COLS)
            assertNotNull(f)
            val det = determinant(Array(COLS) { i -> Array(COLS) { j -> f.v[i, j] } })
            assertTrue(det == BigInteger.ONE || det == -BigInteger.ONE, "det V was $det")
        }
    }

    @Test
    fun `the hermite rows pivot in strictly ascending columns`() {
        val random = Random(1234)
        repeat(REPEATS) {
            val a = bareissEchelon(matrix(random, ROWS, COLS), COLS).rows
            if (a.isEmpty()) return@repeat
            val f = hermiteNormalForm(a, COLS)
            assertNotNull(f)
            // Forward substitution reads each row's last non-zero as that row's own pivot, so two rows
            // must not claim the same column and a later row must not pivot to the left of an earlier one.
            var previous = -1
            for (row in f.h) {
                if (row.isZero) continue
                assertTrue(row.trail > previous, "row pivoted at ${row.trail} after $previous")
                previous = row.trail
            }
        }
    }

    @Test
    fun `a bound derived from the structure contains a witness of the original system`() {
        // The soundness property the whole chain exists for. A witness is built first and the equalities
        // are given its own right-hand sides, so the system provably has that solution; every bound the
        // chain then derives must admit it. A bound that did not would prune away a real model.
        val random = Random(555)
        repeat(REPEATS) {
            val a = matrix(random, ROWS, COLS)
            val witness = Array(COLS) { BigInteger.fromInt(random.nextInt(-6, 7)) }
            val rhs = Array(a.size) { i ->
                var acc = BigInteger.ZERO
                for (k in a[i].index.indices) acc += a[i].value[k] * witness[a[i].index[k]]
                acc
            }
            val mixed = mixedEchelonHermite(a, emptyList(), COLS, rhs)
            if (mixed.equalities.isEmpty()) return@repeat
            val reduced = Array<BigInteger?>(mixed.equalities.size) { mixed.equalityRhs[it] }
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
        const val REPEATS = 60
    }
}
