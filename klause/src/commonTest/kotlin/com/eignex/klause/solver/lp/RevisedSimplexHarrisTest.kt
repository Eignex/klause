package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Harris two-pass ratio test is correctness-neutral: it picks a larger-pivot entering column within
 * a ratio tolerance, so it must reach the SAME certified optimum as the strict minimum-ratio test, in
 * both the plain and bound-flipping selections. Validated against the exact LP optimum oracle.
 */
class RevisedSimplexHarrisTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val vals = LongArray(n) { rng.nextLong(-4, 5) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `harris reaches the same optimum in both ratio-test modes`() {
        val rng = Random(20260622)
        var compared = 0
        repeat(1500) {
            val model = randomModel(rng.nextInt(3, 12), rng.nextInt(3, 12), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val baseline = RevisedSimplex(model, boundFlip = true, harris = false).solve() ?: return@repeat
            compared++
            assertTrue(abs(baseline.objective - opt) < 1e-6, "baseline ${baseline.objective} != opt $opt")
            // Harris on, both ratio-test modes, must match.
            for (bf in booleanArrayOf(true, false)) {
                val h = RevisedSimplex(model, boundFlip = bf, harris = true).solve()
                assertTrue(h != null, "Harris (boundFlip=$bf) failed a feasible LP")
                assertTrue(abs(h.objective - opt) < 1e-6, "Harris (boundFlip=$bf) ${h.objective} != opt $opt")
            }
        }
        assertTrue(compared > 300, "covered only $compared instances")
    }
}
