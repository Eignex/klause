package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.lp.LpPlan
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Per-hull pruning must never change the proven optimum end to end. */
class LpHullPruningOptimumTest {

    private fun unrelatedProductProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4, // a, b, result, x
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(-100, 100), IntDomain(5, 9)),
        factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
    )

    @Test
    fun `pruning preserves the optimum`() {
        val p = unrelatedProductProblem()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        fun optimum(prune: Boolean): Double {
            val res = BacktrackSolver(p).minimize(
                obj,
                BacktrackParams(
                    randomSeed = 1L,
                    lpPlan = LpPlan(bounding = true, productMcCormick = true, pruneHulls = prune),
                ),
            )
            return (res as MinimizeResult.Optimal).objectiveValue
        }
        assertEquals(5.0, optimum(prune = false))
        assertTrue(optimum(prune = true) == 5.0, "per-hull pruning must not change the optimum")
    }
}
