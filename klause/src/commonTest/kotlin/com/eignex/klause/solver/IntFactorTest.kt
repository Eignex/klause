package com.eignex.klause.solver

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.solver.factor.IntEq
import com.eignex.klause.solver.factor.IntGeq
import com.eignex.klause.solver.factor.IntLeq
import com.eignex.klause.solver.factor.IntNeq
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedIntCompare
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntFactorTest {

    private fun stateFor(numIntVars: Int, domains: Array<IntDomain>, factor: Factor): SolverState {
        val problem = Problem(0, numIntVars, domains, listOf(factor))
        val state = SolverState(problem, Random(0))
        state.recompute()
        return state
    }

    @Test
    fun `int leq snaps to bound on repair`() {
        val factor = IntLeq(intVar = 0, bound = 10)
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
        val eq = IntEq(intVar = 0, value = 7)
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
        // x + y ≤ 10, with x in [0..20], y in [0..20], current x=8, y=8 → sum=16, violated.
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
        assertEquals(16, state.intPayload[0])

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty())
        // Pick the first proposed move; for both vars, snap target = 10 - other = 2.
        val move = sink.list.first() as Move.IntSet
        assertEquals(2, move.newValue)
        state.apply(move)
        assertFalse(factor.isViolated(state, 0))
    }

    @Test
    fun `reified int compare tracks aux flips`() {
        // aux ↔ (x ≤ 5).
        val rfc = ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, bound = 5)
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), listOf(rfc))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setInt(0, 3)  // x = 3, aux = true: 3 ≤ 5 holds, satisfied.
        state.recompute()
        assertFalse(rfc.isViolated(state, 0))

        val deltaPred = rfc.deltaIfBoolFlipped(state, 0, 0)
        state.apply(Move.BoolFlip(0))  // aux = false now, but 3 ≤ 5 still holds: violated.
        assertEquals(1, deltaPred)
        assertTrue(rfc.isViolated(state, 0))
    }

    @Test
    fun `int geq delta symmetric`() {
        val factor = IntGeq(intVar = 0, bound = 5)
        val state = stateFor(1, arrayOf(IntDomain(0, 10)), factor)
        state.assignment.setInt(0, 2)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        // Set to 5 makes it satisfied.
        val delta = factor.deltaIfIntSet(state, 0, 0, 5)
        assertEquals(-1, delta)
    }

    @Test
    fun `int neq repair offers both sides`() {
        val factor = IntNeq(intVar = 0, value = 7)
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
