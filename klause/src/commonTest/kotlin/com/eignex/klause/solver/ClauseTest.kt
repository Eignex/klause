package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Clause
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClauseTest {

    private fun stateFor(numBoolVars: Int, factor: Factor): SolverState {
        val problem = Problem(numBoolVars, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.recompute()
        return state
    }

    @Test
    fun `violated when all literals false`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))
        val state = stateFor(2, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, true)
        state.recompute()
        assertTrue(clause.isViolated(state, 0))
    }

    @Test
    fun `delta if flipped matches apply flip`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, false); state.assignment.setBool(1, true); state.assignment.setBool(2, false)
        state.recompute()
        assertTrue(clause.isViolated(state, 0))

        val predictedDelta = clause.deltaIfBoolFlipped(state, 0, 0)
        state.apply(Move.BoolFlip(0))
        assertEquals(-1, predictedDelta)
        assertFalse(clause.isViolated(state, 0))
    }

    @Test
    fun `flipping maintains violation status`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, true); state.assignment.setBool(1, true); state.assignment.setBool(2, false)
        state.recompute()
        assertFalse(clause.isViolated(state, 0))
        state.apply(Move.BoolFlip(0))
        assertFalse(clause.isViolated(state, 0))
        state.apply(Move.BoolFlip(1))
        assertTrue(clause.isViolated(state, 0))
    }
}
