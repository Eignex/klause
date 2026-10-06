package com.eignex.klause.factor.objective

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
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
        val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
            objectiveBoundOverlay(problem.bake(), objective),
        )
        val solver = LocalSearchSolver(overlay, strategy = ProbSat()).apply { objectiveBound = bound }

        val result = solver.minimize(objective, LocalSearchParams(maxFlips = 50_000, randomSeed = 7))

        val best = assertIs<MinimizeResult.BestFound>(result, "the ratchet arm should reach a feasible incumbent")
        assertEquals(1.0, best.objective, "the ratchet should drive the objective to the optimum (one true)")
    }

    @Test
    fun `int set deltas match the cost the applied move produces`() {
        // Mixed-sign int coefficients: the invariant's predicted delta for an IntSet must equal the
        // cost the move actually produces, and the incrementally maintained sum must survive the apply.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            listOf(Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(3, -2))
        val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
            objectiveBoundOverlay(problem.bake(), objective),
        )
        val state = LocalSearchState(overlay, Random(0))
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 0)
        state.recompute()
        bound.tightenBelow(10.0) // sum 3·4 − 2·0 = 12 > bound 9, so the bound factor is violated
        val boundFactorId = overlay.numFactors - 1

        for (move in listOf(Move.IntSet(0, 1), Move.IntSet(1, 4), Move.IntSet(0, 0))) {
            state.assignment.setInt(0, 4)
            state.assignment.setInt(1, 0)
            state.recompute()
            assertTrue(state.violated.contains(boundFactorId), "$move: the bound factor starts violated")
            val predicted = state.netDelta(move)
            val before = state.cost
            state.apply(move)
            assertEquals(state.cost - before, predicted, "$move: predicted delta must match the applied cost")
            // Each move drives the weighted sum to at most 9, so the bound factor must come back satisfied.
            assertTrue(!state.violated.contains(boundFactorId), "$move: the bound factor must be repaired")
            val incremental = state.cost
            state.recompute()
            assertEquals(state.cost, incremental, "$move: the incremental sum must match a full recompute")
        }
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
        val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
            objectiveBoundOverlay(problem.bake(), objective),
        )
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

    @Test
    fun `repair steps an objective variable across a hole`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5).excludeValue(3), IntDomain(0, 5)),
            listOf(Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 10)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))
        val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
            objectiveBoundOverlay(problem.bake(), objective),
        )
        val state = LocalSearchState(overlay, Random(0))
        state.assignment.setInt(0, 2)
        state.recompute()
        bound.tightenBelow(-2.0)
        val boundFactorId = overlay.numFactors - 1
        val sink = MoveSink()

        state.factors[boundFactorId].proposeRepairMoves(state, boundFactorId, sink)

        val xTargets = sink.list.filterIsInstance<Move.IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(listOf(4L), xTargets, "raising x from 2 must skip the hole at 3")
    }

    private fun realObjectiveState(bound: MutableObjectiveBound): LocalSearchState {
        val factor = assertNotNullFactor(
            ObjectiveBoundFactor.of(LinearObjective(realCoefficients = doubleArrayOf(1.0)), bound),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(factor),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        return LocalSearchState(problem.bake(), Random(0)).also { it.restart() }
    }

    private fun assertNotNullFactor(factor: ObjectiveBoundFactor?): ObjectiveBoundFactor =
        kotlin.test.assertNotNull(factor, "an objective with a continuous term has something to bound")

    @Test
    fun `a ratchet over a continuous term keeps its sum in floating point`() {
        assertIs<FloatObjectiveBoundInvariant>(realObjectiveState(MutableObjectiveBound(0L)).factors[0])
    }

    @Test
    fun `a tightened floating-point ratchet reads an unbeaten sum as violated`() {
        val bound = MutableObjectiveBound(0L)
        val state = realObjectiveState(bound)
        state.apply(Move.RealSet(0, 2.5))

        bound.tightenBelow(2.5)
        state.reevaluateFactor(0)

        assertTrue(state.cost > 0L)
    }
}
