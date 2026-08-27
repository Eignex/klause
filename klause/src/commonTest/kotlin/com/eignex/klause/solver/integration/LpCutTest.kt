package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #22/#705: structural cut generation (AllDifferent Hall-set cuts) wired into branch-and-bound over
 *  the sparse LP path — cuts are harvested as a global pool at the root and applied at every node. */
class LpCutTest {

    // AllDifferent over n vars in [0, hi], minimize the sum. Optimum is 0+1+...+(n-1).
    private fun allDiff(n: Int, hi: Int): Problem = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(0, hi.toLong()) },
        factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = hi + 1)),
    )

    @Test
    fun `cuts preserve the optimum`() {
        val p = allDiff(4, 6)
        val obj = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val off = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)))

        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(6.0, off.objectiveValue) // 0+1+2+3
        assertEquals(6.0, on.objectiveValue)
    }

    @Test
    fun `cuts fire on an all-different objective`() {
        val p = allDiff(4, 6)
        val obj = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val on = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)))
        assertTrue(on is MinimizeResult.Optimal)
        assertEquals(6.0, on.objectiveValue)
        assertTrue(on.stats.lp.cuts.sum > 0.0, "expected AllDifferent cuts, got ${on.stats.lp.cuts.sum}")
    }

    @Test
    fun `gomory cuts fire and preserve the optimum on a fractional covering problem`() {
        // Pairwise covering with no AllDifferent, so any cut is a Gomory cut: x_i + x_j >= 3 over
        // [0,3], minimize the sum. The pairwise LP relaxation is fractional, so Gomory separates.
        val rows = ArrayList<Factor>()
        val n = 4
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                rows.add(Linear(intArrayOf(1, 1), intArrayOf(i, j), LinearOp.GE, 3))
            }
        }
        val p = Problem(0, n, Array(n) { IntDomain(0, 3) }, rows.toTypedArray())
        val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })

        val off = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true, gomory = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(off.objectiveValue, on.objectiveValue, "Gomory cuts changed the optimum")
        assertTrue(on.stats.lp.cuts.sum > 0.0, "expected Gomory cuts, got ${on.stats.lp.cuts.sum}")
    }
}
