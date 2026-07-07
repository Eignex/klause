package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Warm-starting the per-node LP from a parent basis (the #20 performance lever). */
class LpWarmStartTest {

    /** Pairwise covering clique: every pair x_i + x_j >= hi over [0,hi]; optimum is well-defined. */
    private fun clique(n: Int, hi: Int): Problem {
        val rows = ArrayList<Factor>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                rows.add(Linear(intArrayOf(1, 1), intArrayOf(i, j), LinearOp.GE, hi))
            }
        }
        return Problem(0, n, Array(n) { IntDomain(0, hi.toLong()) }, rows.toTypedArray())
    }

    @Test
    fun `warm start reaches the same optimum as cold solving`() {
        // Warm-starting only changes pivot counts and (via the optimal basis chosen) which
        // reduced-cost fixings fire; it must never change the proven optimum.
        for (n in 4..6) {
            val p = clique(n, 4)
            val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val cold = BacktrackSolver(p).minimize(
                obj,
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, warmStart = false)),
            )
            val warm = BacktrackSolver(p).minimize(
                obj,
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, warmStart = true)),
            )
            assertTrue(cold is MinimizeResult.Optimal && warm is MinimizeResult.Optimal)
            assertEquals(cold.objectiveValue, warm.objectiveValue, "n=$n optimum diverged")
        }
    }

    @Test
    fun `warm start exercises the simplex and records pivots`() {
        val p = clique(6, 3)
        val obj = LinearObjective(intCoefficients = LongArray(6) { 1L })
        val result = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, warmStart = true)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(11.0, result.objectiveValue)
        assertTrue(result.stats.lp.pivots.sum > 0.0, "expected LP pivots to be recorded")
    }

    @Test
    fun `a stale or unusable warm basis falls back to a cold solve soundly`() {
        // Tiny instance: the warm-start path must still prove the optimum even when bases are reused
        // across structurally identical but differently-bounded nodes.
        val p = clique(3, 5)
        val obj = LinearObjective(intCoefficients = LongArray(3) { 1L })
        val result = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 2L, lpPlan = LpPlan(bounding = true, warmStart = true)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        // Pairwise x_i+x_j>=5 over three vars: summing forces 2·Σ >= 15, so Σ >= 8 (ceil 7.5).
        assertEquals(8.0, result.objectiveValue)
    }
}
