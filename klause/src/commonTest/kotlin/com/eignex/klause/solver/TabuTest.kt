package com.eignex.klause.solver

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabuTest {

    @Test
    fun freshMoveIsNotTaboo() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val state = SolverState(problem, Random(0))
        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 10))
    }

    @Test
    fun appliedMoveIsTabooWithinTenure() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val state = SolverState(problem, Random(0))
        state.apply(Move.BoolFlip(0))
        assertTrue(state.isTaboo(Move.BoolFlip(0), tenure = 5), "freshly flipped var must be taboo")
    }

    @Test
    fun tenureExpiresAfterEnoughSteps() {
        val problem = Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val state = SolverState(problem, Random(0))
        state.apply(Move.BoolFlip(0))
        // Flip four other vars to advance the step counter.
        state.apply(Move.BoolFlip(1))
        state.apply(Move.BoolFlip(2))
        state.apply(Move.BoolFlip(3))
        state.apply(Move.BoolFlip(4))
        // Total steps now = 5; the original flip was at step 1; tenure = 4 means taboo iff
        // step - lastTouched[0] < 4 → 5 - 1 < 4 is false. Should no longer be taboo.
        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 4))
    }

    @Test
    fun tabuTenureZeroDisables() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val state = SolverState(problem, Random(0))
        state.apply(Move.BoolFlip(0))
        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 0))
    }
}
