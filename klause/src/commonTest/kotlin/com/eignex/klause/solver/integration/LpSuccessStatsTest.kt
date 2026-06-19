package com.eignex.klause.solver.integration

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertTrue

/** The LP-success measurements surfaced in `-s` (#472 follow-up): solve count, prune split, root
 *  bound and LP time must populate on an LP-bounded cumulative minimize. */
class LpSuccessStatsTest {

    /** `min M  s.t.  M ≥ startᵢ + durᵢ,  cumulative(...)`; ints 0..n-1 starts, n is the makespan. */
    private fun makespanProblem(n: Int, durations: IntArray, resources: IntArray, capacity: Int, hi: Int): Problem {
        val domains = Array(n + 1) { if (it < n) IntDomain(0, hi) else IntDomain(0, hi + 6) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, durations[i]))
        factors.add(Cumulative(IntArray(n) { it }, durations, resources, capacity))
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    @Test
    fun `lp success counters populate on a bounded cumulative minimize`() {
        val n = 5
        val p = makespanProblem(n, intArrayOf(3, 2, 4, 2, 3), intArrayOf(1, 2, 1, 2, 1), capacity = 2, hi = 8)
        val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
        val params = BacktrackParams(randomSeed = 7L, lpConfig = LpConfig.AGGRESSIVE)
        val res = BacktrackSolver(p).minimize(obj, params)
        assertTrue(res is MinimizeResult.Optimal, "expected an optimum, got $res")
        val s = res.stats

        assertTrue(s.lpSolves.sum > 0.0, "no LP solves recorded: lpSolves=${s.lpSolves.sum}")
        // Infeasible prunes are a subset of the total prunes.
        assertTrue(
            s.lpInfeasible.sum <= s.lpPruned.sum,
            "lpInfeasible ${s.lpInfeasible.sum} exceeds lpPruned ${s.lpPruned.sum}",
        )
        // The root bound is a sound lower bound on the minimisation optimum.
        assertTrue(s.rootLpBound.isFinite(), "root LP bound was not captured")
        assertTrue(
            s.rootLpBound <= res.objective + 1e-6,
            "root LP bound ${s.rootLpBound} exceeds optimum ${res.objective}",
        )
        assertTrue(s.lpMs >= 0L, "negative LP time ${s.lpMs}")
    }
}
