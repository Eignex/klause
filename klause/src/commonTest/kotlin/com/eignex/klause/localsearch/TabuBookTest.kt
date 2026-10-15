package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabuBookTest {

    @Test
    fun `all compound parts share the accepted move tabu window`() {
        val problem = Problem(5, 0, emptyArray(), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))

        state.apply(Move.Compound((0 until 5).map { Move.BoolFlip(it) }))

        for (variable in 0 until 5) assertTrue(state.tabu.isTaboo(Move.BoolFlip(variable), tenure = 1))
    }

    @Test
    fun `an unchanged integer set does not consume tabu tenure`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.apply(Move.BoolFlip(0))

        state.apply(Move.IntSet(0, state.assignment.intValue(0)))

        assertTrue(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 1))
    }

    @Test
    fun `fresh move is not taboo`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem.bake(), Random(0))
        assertFalse(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 10))
    }

    @Test
    fun `applied move is taboo within tenure`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem.bake(), Random(0))
        state.apply(Move.BoolFlip(0))
        assertTrue(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 5), "freshly flipped var must be taboo")
    }

    @Test
    fun `tenure expires after enough steps`() {
        val problem = Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem.bake(), Random(0))
        state.apply(Move.BoolFlip(0))

        state.apply(Move.BoolFlip(1))
        state.apply(Move.BoolFlip(2))
        state.apply(Move.BoolFlip(3))
        state.apply(Move.BoolFlip(4))

        assertFalse(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 4))
    }

    @Test
    fun `tabu tenure zero disables`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val state = LocalSearchState(problem.bake(), Random(0))
        state.apply(Move.BoolFlip(0))
        assertFalse(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 0))
    }
}
