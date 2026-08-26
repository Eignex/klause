package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-arm sharing of globally-valid level-0 variable bounds: an arm publishes the root tightenings it
 * proves hold at every solution, and importing a shared bound only tightens a domain — never changes the
 * optimum. (`x ∈ [0, 9]` with `x ≥ 3`, minimizing `x`; the proven optimum is 3.)
 */
class GlobalVarBoundSharingTest {

    private fun problem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 9)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
    )

    /** Triangle vertex cover: `cost = x0+x1+x2` over {0,1}³ with the three pair-covering rows. Every
     *  solution needs ≥ 2 ones, so `cost ≥ 2` — a bound bake propagation cannot derive but variable
     *  shaving (LP + propagation) proves, so it is a beyond-bake tightening worth sharing. */
    private fun triangleCover() = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `a beyond-bake shaving tightening reaches the sink`() {
        val published = HashMap<Int, Pair<Long, Long>>()
        val res = BacktrackSolver(triangleCover().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)), // minimize cost (var 3)
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, variableShaving = true),
                globalVarBoundSink = { v, lo, hi -> published[v] = lo to hi },
            ),
        )
        assertTrue(res is MinimizeResult.Optimal && res.objectiveValue == 2.0)
        assertEquals(2, published[3]?.first, "shaving proves cost >= 2, which must be published, got $published")
    }

    @Test
    fun `importing a shared bound preserves the optimum`() {
        val res = BacktrackSolver(problem().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(1)),
            BacktrackParams(
                randomSeed = 1L,
                globalVarLowerSupplier = { v -> if (v == 0) 3L else Int.MIN_VALUE.toLong() },
                globalVarUpperSupplier = { Int.MAX_VALUE.toLong() },
            ),
        )
        assertTrue(
            res is MinimizeResult.Optimal && res.objectiveValue == 3.0,
            "importing a valid bound keeps the optimum",
        )
    }
}
