package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TabuFilterTest {

    /** Build a tiny LS state and step it once so `lastTouched` is meaningful. */
    private fun smallState(): LocalSearchState {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        // Touch var 0 so isTaboo(BoolFlip(0), >=1) becomes true.
        state.apply(Move.BoolFlip(0))
        return state
    }

    @Test
    fun `filter strips tabu candidates when alternatives exist`() {
        val state = smallState()
        val filter = TabuFilter(tenure = 10)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        val out = filter.filter(state, moves)
        assertEquals(listOf<Move>(Move.BoolFlip(1)), out)
    }

    @Test
    fun `filter falls back to full set when every move is tabu`() {
        val state = smallState()
        val filter = TabuFilter(tenure = 10)
        // The only candidate is tabu; aspiration fallback must drop the filter, not return empty.
        val moves = listOf<Move>(Move.BoolFlip(0))
        val out = filter.filter(state, moves)
        assertEquals(moves, out)
    }

    @Test
    fun `aspiration admits tabu move that strictly improves cost`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        // Touch var 0 twice so it is tabu, but flipping it now strictly improves cost.
        state.apply(Move.BoolFlip(0))
        state.apply(Move.BoolFlip(0))

        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        val out = filter.filter(state, moves)
        assertEquals(2, out.size, "OrImproving should admit the strictly-improving tabu move; got $out")
    }

    @Test
    fun `dynamic tenure overrides the static value`() {
        val state = smallState()
        val filter = TabuFilter(tenure = 10, dynamicTenure = { 0 })
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        assertSame(moves, filter.filter(state, moves))
    }

    @Test
    fun `Probabilistic aspiration rejects out-of-range rate at construction`() {
        for (rate in listOf(-0.1, 1.5)) {
            assertFailsWith<IllegalArgumentException>("rate $rate must be rejected") {
                AspirationCriterion.Probabilistic(rate = rate)
            }
        }
    }
}
