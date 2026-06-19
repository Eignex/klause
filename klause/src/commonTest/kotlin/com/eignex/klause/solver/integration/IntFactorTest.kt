package com.eignex.klause.solver.integration

import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.solver.*
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.reifiedIntCompare
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntFactorTest {

    private fun stateFor(numIntVars: Int, domains: Array<IntDomain>, factor: Factor): LocalSearchState {
        val problem = Problem(0, numIntVars, domains, listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        return state
    }

    @Test
    fun `int leq snaps to bound on repair`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)
        val state = stateFor(1, arrayOf(IntDomain(0, 100)), factor)
        state.assignment.setInt(0, 50)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertEquals(1, sink.list.size)
        val move = sink.list[0] as Move.IntSet
        assertEquals(0, move.varId)
        assertEquals(10, move.newValue)

        state.apply(move)
        assertFalse(factor.isViolated(state, 0))
        assertEquals(0, state.cost)
    }

    @Test
    fun `int eq and int neq incremental update`() {
        val eq = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)
        val state = stateFor(1, arrayOf(IntDomain(0, 20)), eq)
        state.assignment.setInt(0, 7)
        state.recompute()
        assertFalse(eq.isViolated(state, 0))

        val deltaPred = eq.deltaIfIntSet(state, 0, 0, 8)
        state.apply(Move.IntSet(0, 8))
        assertEquals(1, deltaPred)
        assertTrue(eq.isViolated(state, 0))
    }

    @Test
    fun `linear le repair snaps a variable`() {
        val factor = Linear(
            coeffs = intArrayOf(1, 1),
            vars = intArrayOf(0, 1),
            op = LinearOp.LE,
            bound = 10,
        )
        val state = stateFor(2, arrayOf(IntDomain(0, 20), IntDomain(0, 20)), factor)
        state.assignment.setInt(0, 8)
        state.assignment.setInt(1, 8)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        assertEquals(16L, state.longPayload[0])

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty())

        val move = sink.list.first() as Move.IntSet
        assertEquals(2, move.newValue)
        state.apply(move)
        assertFalse(factor.isViolated(state, 0))
    }

    @Test
    fun `reified int compare tracks aux flips`() {
        val rfc = reifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, 5)
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), listOf(rfc))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setInt(0, 3)
        state.recompute()
        assertFalse(rfc.isViolated(state, 0))

        val deltaPred = rfc.deltaIfBoolFlipped(state, 0, 0)
        state.apply(Move.BoolFlip(0))
        assertEquals(1, deltaPred)
        assertTrue(rfc.isViolated(state, 0))
    }

    @Test
    fun `int geq delta symmetric`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)
        val state = stateFor(1, arrayOf(IntDomain(0, 10)), factor)
        state.assignment.setInt(0, 2)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        // Graded: GE residual drops from (5-2)=3 to 0, so the degree delta is -3 (not the
        // old binary -1). This is the gradient CBLS descends on.
        val delta = factor.deltaIfIntSet(state, 0, 0, 5)
        assertEquals(-3, delta)
    }

    @Test
    fun `int neq repair offers both sides`() {
        val factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 7)
        val state = stateFor(1, arrayOf(IntDomain(0, 20)), factor)
        state.assignment.setInt(0, 7)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val targets = sink.list.filterIsInstance<Move.IntSet>().map { it.newValue }.toSet()
        assertEquals(setOf(6, 8), targets)
    }
}
