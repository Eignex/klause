package com.eignex.klause.factor.objective

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `surgical reevaluate after a bound tighten matches a full recompute`() {
        // The ratchet reconciles only the bound factor when the bound tightens (no move occurred). This
        // asserts that surgical reevaluateFactor leaves cost, violated membership, and the break/make
        // vectors identical to a full recompute — i.e. the O(numFactors) recompute is safe to skip.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1, 1, 1))
        val (overlay, bound) = assertIs<Pair<Problem, MutableObjectiveBound>>(objectiveBoundOverlay(problem, objective))
        val state = LocalSearchState(overlay, Random(0))
        for (b in 0 until 3) state.assignment.setBool(b, true) // feasible: three true, objective 3
        state.recompute()

        bound.tightenBelow(3.0) // sum 3 > bound 2, so the bound factor turns violated with no move
        val boundFactorId = overlay.numFactors - 1
        state.reevaluateFactor(boundFactorId)
        val surgicalCost = state.cost
        val surgicalViolated = state.violated.contains(boundFactorId)
        val surgicalBreak = state.boolBreakCount.copyOf()
        val surgicalMake = state.boolMakeCount.copyOf()

        state.recompute() // ground truth from the same assignment + tightened bound
        assertEquals(state.cost, surgicalCost, "cost must match a full recompute")
        assertEquals(state.violated.contains(boundFactorId), surgicalViolated, "violated membership must match")
        assertTrue(state.boolBreakCount.contentEquals(surgicalBreak), "break vector must match")
        assertTrue(state.boolMakeCount.contentEquals(surgicalMake), "make vector must match")
    }
}
