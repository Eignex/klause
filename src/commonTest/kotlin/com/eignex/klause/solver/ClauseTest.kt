package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Clause
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClauseTest {

    private fun stateFor(numVars: Int, factor: com.eignex.klause.solver.Factor): SolverState {
        val problem = Problem(numVars, listOf(factor))
        val state = SolverState(problem, Random(0))
        state.recompute()
        return state
    }

    @Test
    fun violatedWhenAllLiteralsFalse() {
        // (x0 ∨ ¬x1) — falsified by x0=F, x1=T
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))
        val state = stateFor(2, clause)
        state.assignment[0] = false
        state.assignment[1] = true
        state.recompute()
        assertTrue(clause.isViolated(state, 0))
    }

    @Test
    fun deltaIfFlippedMatchesApplyFlip() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment[0] = false; state.assignment[1] = true; state.assignment[2] = false
        state.recompute()
        assertTrue(clause.isViolated(state, 0))

        val predictedDelta = clause.deltaIfFlipped(state, 0, 0)
        state.flip(0)
        // After flipping x0 to true, clause becomes satisfied: predicted delta should be -1.
        assertEquals(-1, predictedDelta)
        assertFalse(clause.isViolated(state, 0))
    }

    @Test
    fun flippingMaintainsCount() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment[0] = true; state.assignment[1] = true; state.assignment[2] = false
        state.recompute()
        assertEquals(2, state.intPayload[0])
        state.flip(0)
        assertEquals(1, state.intPayload[0])
        state.flip(1)
        assertEquals(0, state.intPayload[0])
        assertTrue(clause.isViolated(state, 0))
    }
}
