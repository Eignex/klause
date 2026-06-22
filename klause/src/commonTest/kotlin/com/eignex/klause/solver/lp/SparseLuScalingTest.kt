package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Row equilibration in [SparseLu] must be **transparent**: `ftran`, `btran`, and `determinant` return
 * the same mathematical result with equilibration on as off (only the conditioning differs). Any error
 * in the scale/unscale bookkeeping is caught here rather than shipped. End-to-end, [RevisedSimplex]
 * (which always equilibrates) must reach the same optimum on badly-scaled models.
 */
class SparseLuScalingTest {

    private fun randomMatrix(m: Int, rng: Random): Array<HashMap<Int, Double>> {
        // A diagonally-dominant matrix with widely varying row magnitudes (so equilibration bites).
        val rows = Array(m) { HashMap<Int, Double>() }
        for (i in 0 until m) {
            val scale = 2.0.let { b -> b * (1 shl rng.nextInt(0, 12)) } // very different per-row magnitudes
            for (j in 0 until m) {
                if (i == j) {
                    rows[i][j] = scale * (rng.nextDouble(2.0, 5.0))
                } else if (rng.nextDouble() < 0.4) {
                    rows[i][j] = scale * rng.nextDouble(-1.0, 1.0)
                }
            }
        }
        return rows
    }

    @Test
    fun `equilibration is transparent for ftran btran and determinant`() {
        val rng = Random(20260622)
        repeat(400) { _ ->
            val m = rng.nextInt(2, 12)
            val base = randomMatrix(m, rng)
            // Deep-copy for the second factorization (factorize eliminates in place).
            val copy = Array(m) { HashMap(base[it]) }
            val plain = SparseLu.factorize(base, m, equilibrate = false) ?: return@repeat
            val scaled = assertNotNull(SparseLu.factorize(copy, m, equilibrate = true), "scaled factorization")

            val b = DoubleArray(m) { rng.nextDouble(-10.0, 10.0) }
            val fp = plain.ftran(b)
            val fs = scaled.ftran(b)
            val bp = plain.btran(b)
            val bs = scaled.btran(b)
            for (i in 0 until m) {
                val tol = 1e-7 * (1.0 + abs(fp[i]))
                assertTrue(abs(fp[i] - fs[i]) <= tol, "ftran[$i]: plain ${fp[i]} vs scaled ${fs[i]}")
                val tolB = 1e-7 * (1.0 + abs(bp[i]))
                assertTrue(abs(bp[i] - bs[i]) <= tolB, "btran[$i]: plain ${bp[i]} vs scaled ${bs[i]}")
            }
            val dp = plain.determinant()
            val ds = scaled.determinant()
            assertTrue(abs(dp - ds) <= 1e-6 * (1.0 + abs(dp)), "determinant: plain $dp vs scaled $ds")
        }
    }

    @Test
    fun `revised simplex always-scaled reaches the same optimum`() {
        val rng = Random(7)
        var compared = 0
        repeat(1500) { _ ->
            val b = LpBuilder()
            val n = rng.nextInt(3, 10)
            repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
            val cols = IntArray(n) { it }
            repeat(rng.nextInt(3, 10)) {
                val vals = LongArray(n) { rng.nextLong(-4, 5) }
                b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
            }
            val model = b.build(Sense.MINIMIZE)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val scaled = RevisedSimplex(model).solve() ?: return@repeat
            compared++
            assertTrue(abs(scaled.objective - opt) < 1e-6, "scaled ${scaled.objective} != opt $opt")
        }
        assertTrue(compared > 300, "covered only $compared instances")
    }
}
