package com.eignex.klause.factor.objective

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObjectiveBoundFactorTest {

    @Test
    fun `Boolean objective bound repairs should backsolve maintained unit penalties`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val sweep = assertNotNull(
            DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )
        for (weight in listOf(3L, (1L shl 62) + 1)) {
            val objective = LinearObjective(boolWeights = longArrayOf(0, weight))
            val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
                objectiveBoundOverlay(problem.bake(), objective),
            )
            val state = LocalSearchState(overlay, Random(3))
            state.invariants = sweep.network(0, 2)
            sweep.sweep(state.assignment, state.rootDomains, overlay.factors)
            bound.tightenBelow(3.0)
            state.recompute()
            val proposals = MoveSink().also { it.setInvariants(state.invariants) }

            state.factors[overlay.numFactors - 1].proposeRepairMoves(state, overlay.numFactors - 1, proposals)
            state.apply(proposals.list.single())

            assertEquals(true, state.assignment.boolValue(0))
            assertEquals(false, state.assignment.boolValue(1))
            assertEquals(0L, state.cost)
            assertEquals(0L, objective.evaluateLong(state.assignment))
        }
    }

    @Test
    fun `a bool flip should read the flipped variable's own weight`() {
        val clause = Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))
        val problem = Problem(3, 0, emptyArray<IntDomain>(), listOf(clause))
        val (overlay, bound) = assertIs<Pair<BakedProblem, MutableObjectiveBound>>(
            objectiveBoundOverlay(problem.bake(), LinearObjective(boolWeights = longArrayOf(1, 5, 9))),
        )
        val state = LocalSearchState(overlay, Random(0))
        state.recompute()
        bound.tightenBelow(1.0)
        state.recompute()

        val deltas = (0 until 3).map { state.netDelta(Move.BoolFlip(it)) }

        assertEquals(listOf(1L, 5L, 9L), deltas)
    }

    @Test
    fun `int set deltas should match the cost the applied move produces`() {
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
        bound.tightenBelow(10.0)
        val boundFactorId = overlay.numFactors - 1

        for (move in listOf(Move.IntSet(0, 1), Move.IntSet(1, 4), Move.IntSet(0, 0))) {
            state.assignment.setInt(0, 4)
            state.assignment.setInt(1, 0)
            state.recompute()
            assertTrue(state.violated.contains(boundFactorId), "$move")
            val predicted = state.netDelta(move)
            val before = state.cost
            state.apply(move)
            assertEquals(state.cost - before, predicted, "$move")
            assertTrue(!state.violated.contains(boundFactorId), "$move")
            val incremental = state.cost
            state.recompute()
            assertEquals(state.cost, incremental, "$move")
        }
    }

    @Test
    fun `repair should step an objective variable across a hole`() {
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
        assertEquals(listOf(4L), xTargets)
    }
}
