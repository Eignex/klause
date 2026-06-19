package com.eignex.klause.solver.integration

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertTrue

/** #562: when the per-node LP never prunes, the dynamic auto-off disables it after a warmup window
 *  — so `lpSolves` stays bounded even though search explores many more nodes. */
class LpAutoOffTest {

    /** Pigeonhole: `n` variables over `n-1` values with pairwise `x_i ≠ x_j`. The `≠` rows are not
     *  LP-relaxable (skipped), so the LP relaxation carries no useful row — its bound never prunes —
     *  while search explores many nodes proving infeasibility. Auto-off must cap `lpSolves`. */
    @Test
    fun `auto-off disables a never-pruning LP after the warmup`() {
        val n = 7
        val factors = ArrayList<Factor>()
        for (i in 0 until n) {
            for (j in i + 1 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(i, j), LinearOp.NE, 0))
        }
        // One always-true row so the relaxation is non-empty and the simplex actually runs each node;
        // its bound (Σx ≥ 0) is hopelessly loose, so it can never prune.
        factors.add(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.GE, 0))
        val p = Problem(0, n, Array(n) { IntDomain(0, n - 2) }, factors.toTypedArray()) // n vars, n-1 values
        val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
        val res = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpConfig = LpConfig.AGGRESSIVE))

        assertTrue(res is MinimizeResult.Infeasible, "pigeonhole is infeasible, got $res")
        val lpSolves = res.stats.lpSolves.sum
        val lpPruned = res.stats.lpPruned.sum
        val nodes = res.stats.nodes.sum
        assertTrue(lpPruned == 0.0, "the row-less LP should never prune, pruned=$lpPruned")
        assertTrue(nodes > 150, "expected a long search, got $nodes nodes")
        assertTrue(lpSolves <= 100, "auto-off should cap lpSolves near the warmup, got $lpSolves over $nodes nodes")
    }
}
