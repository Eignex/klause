package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Devex pricing is correctness-neutral: it must reach the SAME certified optimum as the default
 * Dantzig rule on every instance (only the pivot path differs), validated against the exact LP
 * optimum oracle. An aggregate pivot-count guard catches a gross pricing regression.
 */
class RevisedSimplexDevexTest {

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
    fun `devex reaches the same optimum as dantzig`() {
        val rng = Random(20260622)
        var compared = 0
        var dantzigPivots = 0L
        var devexPivots = 0L
        repeat(1500) {
            val model = randomModel(rng.nextInt(3, 12), rng.nextInt(3, 12), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val dantzig = RevisedSimplex(model, pricing = SimplexPricing.DANTZIG).solve() ?: return@repeat
            val devex = RevisedSimplex(model, pricing = SimplexPricing.DEVEX).solve()
            assertTrue(devex != null, "Devex failed to solve a feasible LP that Dantzig solved")
            compared++
            assertTrue(abs(dantzig.objective - opt) < 1e-6, "Dantzig objective ${dantzig.objective} != opt $opt")
            assertTrue(abs(devex.objective - opt) < 1e-6, "Devex objective ${devex.objective} != opt $opt")
            dantzigPivots += dantzig.pivots
            devexPivots += devex.pivots
        }
        assertTrue(compared > 300, "covered only $compared instances")
        // Devex must not be grossly worse than Dantzig overall (it is usually competitive or better).
        assertTrue(
            devexPivots <= dantzigPivots * 3 + compared,
            "Devex pivots $devexPivots vs Dantzig $dantzigPivots — gross regression",
        )
    }
}
