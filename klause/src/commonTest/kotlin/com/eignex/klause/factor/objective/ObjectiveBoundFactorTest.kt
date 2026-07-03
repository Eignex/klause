package com.eignex.klause.factor.objective

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The objective-as-constraint ratchet ([ObjectiveBoundFactor] + [objectiveBoundOverlay]): a
 * violation-native SAT-style arm (probSAT) made a COP optimizer by repairing an `objective ≤ incumbent`
 * factor, driven down by the minimize engine's per-incumbent bound tightening.
 */
class ObjectiveBoundFactorTest {

    @Test
    fun `probsat ratchets the objective down by repairing the bound factor`() {
        // atLeastOne(b0, b1, b2): feasible ⇔ at least one true; objective = count of trues, optimum 1.
        // Plain probSAT bails at the first feasible; with the overlay it repairs "beat the incumbent"
        // and the engine ratchets the bound down to the single-true optimum.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1, 1, 1))
        val (overlay, bound) = assertIs<Pair<Problem, MutableObjectiveBound>>(objectiveBoundOverlay(problem, objective))
        val solver = LocalSearchSolver(overlay, strategy = ProbSat()).apply { objectiveBound = bound }

        val result = solver.minimize(objective, LocalSearchParams(maxFlips = 50_000, randomSeed = 7))

        val best = assertIs<MinimizeResult.BestFound>(result, "the ratchet arm should reach a feasible incumbent")
        assertEquals(1.0, best.objective, "the ratchet should drive the objective to the optimum (one true)")
    }
}
