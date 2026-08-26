package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertTrue

/** The LP-success measurements surfaced in `-s` (#472 follow-up): solve count, prune split, root
 *  bound and LP time must populate on an LP-bounded cumulative minimize. */
class LpSuccessStatsTest {

    /** `min M  s.t.  M ≥ startᵢ + durᵢ,  cumulative(...)`; ints 0..n-1 starts, n is the makespan. */
    private fun makespanProblem(n: Int, durations: LongArray, resources: LongArray, capacity: Long, hi: Int): Problem {
        val domains = Array(n + 1) { if (it < n) IntDomain(0, hi.toLong()) else IntDomain(0, (hi + 6).toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(longArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, durations[i]))
        factors.add(Cumulative(IntArray(n) { it }, durations, resources, capacity))
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    @Test
    fun `lp success counters populate on a bounded cumulative minimize`() {
        val n = 5
        val p = makespanProblem(n, longArrayOf(3, 2, 4, 2, 3), longArrayOf(1, 2, 1, 2, 1), capacity = 2L, hi = 8)
        val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
        val params = BacktrackParams(randomSeed = 7L, lpConfig = LpConfig.AGGRESSIVE)
        val res = BacktrackSolver(p.bake()).minimize(obj, params)
        assertTrue(res is MinimizeResult.Optimal, "expected an optimum, got $res")
        val s = res.stats

        assertTrue(s.lp.solves.sum > 0.0, "no LP solves recorded: lpSolves=${s.lp.solves.sum}")
        // Infeasible prunes are a subset of the total prunes.
        assertTrue(
            s.lp.infeasible.sum <= s.lp.pruned.sum,
            "lpInfeasible ${s.lp.infeasible.sum} exceeds lpPruned ${s.lp.pruned.sum}",
        )
        // The root bound is a sound lower bound on the minimisation optimum.
        assertTrue(s.lp.rootBound.isFinite(), "root LP bound was not captured")
        assertTrue(
            s.lp.rootBound <= res.objective + 1e-6,
            "root LP bound ${s.lp.rootBound} exceeds optimum ${res.objective}",
        )
        assertTrue(s.lp.ms >= 0L, "negative LP time ${s.lp.ms}")
    }
}
