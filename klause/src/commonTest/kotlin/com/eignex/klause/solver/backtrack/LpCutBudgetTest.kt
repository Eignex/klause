package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #565: budget- and benefit-aware cut separation — a per-node cut cap and a staleness break, both
 *  preserving the optimum. */
class LpCutBudgetTest {

    // Pairwise covering x_i + x_j >= 3 over [0,3], minimize the sum. The LP relaxation is fractional
    // (all 1.5), so Gomory/MIR separate over many rounds — a cut-heavy instance. Optimum is 11.
    private fun covering(n: Int): Problem {
        val rows = ArrayList<Factor>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                rows.add(Linear(intArrayOf(1, 1), intArrayOf(i, j), LinearOp.GE, 3))
            }
        }
        return Problem(0, n, Array(n) { IntDomain(0, 3) }, rows.toTypedArray())
    }

    private val n = 6
    private val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
    private val optimum = 11.0

    private fun run(cap: Int, gain: Double): MinimizeResult.Optimal {
        val r = BacktrackSolver(covering(n)).minimize(
            obj,
            BacktrackParams(
                randomSeed = 1L,
                lpBounding = true,
                lpCuts = true,
                lpGomory = true,
                lpMir = true,
                lpMaxCutsPerNode = cap,
                lpCutMinGain = gain,
            ),
        )
        assertTrue(r is MinimizeResult.Optimal, "expected optimum, got $r")
        return r
    }

    @Test
    fun `the per-node budget caps cuts per node and preserves the optimum`() {
        val cap = 4
        val capped = run(cap = cap, gain = 0.0) // staleness off, so the budget is the only limiter
        val uncapped = run(cap = 1_000_000, gain = 0.0)
        assertEquals(optimum, capped.objectiveValue, "the budget changed the optimum")
        assertEquals(optimum, uncapped.objectiveValue)
        // The cap's guarantee: no node adds more than `cap` cuts, so the total is bounded by cap×nodes.
        assertTrue(
            capped.stats.lpCuts.sum <= cap * capped.stats.nodes.sum,
            "per-node cap violated: ${capped.stats.lpCuts.sum} cuts over ${capped.stats.nodes.sum} nodes",
        )
        // And the cap genuinely bites: uncapped separation puts more cuts on the same search tree.
        assertEquals(uncapped.stats.nodes.sum, capped.stats.nodes.sum, "node count diverged; comparison unsound")
        assertTrue(
            capped.stats.lpCuts.sum < uncapped.stats.lpCuts.sum,
            "the cap removed no cuts: capped=${capped.stats.lpCuts.sum} uncapped=${uncapped.stats.lpCuts.sum}",
        )
    }

    @Test
    fun `staleness stops separating early and preserves the optimum`() {
        val stale = run(cap = 1_000_000, gain = 0.5) // steep gain threshold ends separation early
        val full = run(cap = 1_000_000, gain = 0.0)
        assertEquals(optimum, stale.objectiveValue, "staleness changed the optimum")
        assertEquals(optimum, full.objectiveValue)
        assertTrue(
            stale.stats.lpCuts.sum < full.stats.lpCuts.sum,
            "staleness should separate fewer cuts: stale=${stale.stats.lpCuts.sum} full=${full.stats.lpCuts.sum}",
        )
    }
}
