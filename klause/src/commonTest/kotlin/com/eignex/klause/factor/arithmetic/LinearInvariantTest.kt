package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearInvariantTest {

    @Test
    fun `inequality repairs round across holes toward satisfaction`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            for (coefficient in listOf(-1, 1)) {
                val decreasing = (op == LinearOp.LE) == (coefficient > 0)
                val target = if (decreasing) 0L else 3L
                val bound = coefficient * if (decreasing) 2 else 1
                val problem = Problem(
                    0, 1, arrayOf(IntDomain(0, 3).excludeValue(1).excludeValue(2)),
                    arrayOf<Factor>(Linear(intArrayOf(coefficient), intArrayOf(0), op, bound)),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                state.assignment.setInt(0, 3L - target)
                state.recompute()
                val before = state.cost

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val move = state.moveSink.list.filterIsInstance<IntSet>().single()
                val predicted = state.netDelta(move)
                state.apply(move)

                assertEquals(target, move.newValue, "$op $coefficient")
                assertEquals(-before, predicted)
                assertEquals(0L, state.cost)
                state.recompute()
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `unreachable inequality bounds retain partial endpoint repairs`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            for (coefficient in listOf(-1, 1)) {
                val decreasing = (op == LinearOp.LE) == (coefficient > 0)
                val target = if (decreasing) 0L else 3L
                val bound = coefficient * if (decreasing) 5 else 15
                val problem = Problem(
                    0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 10)),
                    arrayOf<Factor>(Linear(intArrayOf(coefficient, coefficient), intArrayOf(0, 1), op, bound)),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                state.assignment.setInt(0, 3L - target)
                state.assignment.setInt(1, if (decreasing) 10L else 0L)
                state.recompute()
                val before = state.cost

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val move = state.moveSink.list.filterIsInstance<IntSet>().single { it.varId == 0 }
                val predicted = state.netDelta(move)
                state.apply(move)
                val committed = state.cost

                assertEquals(target, move.newValue, "$op $coefficient")
                assertTrue(predicted < 0L)
                assertTrue(committed > 0L)
                assertEquals(predicted, committed - before)
                state.recompute()
                assertEquals(committed, state.cost)
            }
        }
    }

    @Test
    fun `not-equal repair steps across a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 6)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        val xTargets = sink.list.filterIsInstance<IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(setOf(2L, 5L), xTargets.toSet(), "the step below 4 must skip the hole at 3")
    }
}
