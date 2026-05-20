package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.LocalSearchState

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabuTest {

    @Test
    fun `fresh move is not taboo`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem, Random(0))
        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 10))
    }

    @Test
    fun `applied move is taboo within tenure`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem, Random(0))
        state.apply(Move.BoolFlip(0))
        assertTrue(state.isTaboo(Move.BoolFlip(0), tenure = 5), "freshly flipped var must be taboo")
    }

    @Test
    fun `tenure expires after enough steps`() {
        val problem = Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem, Random(0))
        state.apply(Move.BoolFlip(0))

        state.apply(Move.BoolFlip(1))
        state.apply(Move.BoolFlip(2))
        state.apply(Move.BoolFlip(3))
        state.apply(Move.BoolFlip(4))

        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 4))
    }

    @Test
    fun `tabu tenure zero disables`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem, Random(0))
        state.apply(Move.BoolFlip(0))
        assertFalse(state.isTaboo(Move.BoolFlip(0), tenure = 0))
    }
}
