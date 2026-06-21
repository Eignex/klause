package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClauseTest {

    private fun stateFor(numBoolVars: Int, factor: Factor): LocalSearchState {
        val problem = Problem(numBoolVars, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
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
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta if flipped matches apply flip`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, true)
        state.assignment.setBool(2, false)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))

        val predictedDelta = state.factors[0].deltaIfBoolFlipped(state, 0, 0)
        state.apply(Move.BoolFlip(0))
        assertEquals(-1, predictedDelta)
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `flipping maintains violation status`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val state = stateFor(3, clause)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.assignment.setBool(2, false)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(0))
        assertFalse(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(1))
        assertTrue(state.factors[0].isViolated(state, 0))
    }
}
