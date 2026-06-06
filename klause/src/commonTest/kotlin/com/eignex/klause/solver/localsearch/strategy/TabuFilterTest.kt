package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TabuFilterTest {

    /** Build a tiny LS state and step it once so `lastTouched` is meaningful. */
    private fun smallState(): LocalSearchState {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        // Touch var 0 so isTaboo(BoolFlip(0), >=1) becomes true.
        state.apply(Move.BoolFlip(0))
        return state
    }

    @Test
    fun `disabled filter is the identity`() {
        val state = smallState()
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        assertSame(moves, TabuFilter.Disabled.filter(state, moves), "Disabled should return the input list unchanged")
    }

    @Test
    fun `filter returns input list when no move is tabu`() {
        val state = smallState()
        val filter = TabuFilter(tenure = 0)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        assertSame(moves, filter.filter(state, moves))
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
        val state = LocalSearchState(problem, Random(0))
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
    fun `OrImprovesBestEver admits a tabu move that would beat the historical minimum`() {
        val state = smallState()
        val initialBest = state.bestCostSeen
        state.apply(Move.BoolFlip(1))
        val current = state.bestCostSeen
        assertTrue(current <= initialBest, "best-cost should monotone-decrease, got $initialBest -> $current")

        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImprovesBestEver)
        val moves = listOf<Move>(Move.BoolFlip(0))
        val out = filter.filter(state, moves)
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `Cooling aspiration admits liberally at high T and converges to never at low T`() {
        val state = smallState()
        val cooling = AspirationCriterion.Cooling(initialTemperature = 100.0, coolingRate = 0.5, minTemperature = 1e-9)
        val moves = listOf<Move>(Move.BoolFlip(0))
        val highTfilter = TabuFilter(tenure = 10, aspiration = cooling)
        var earlyAdmits = 0
        repeat(50) {
            cooling.reset()
            if (highTfilter.filter(state, moves) == moves) earlyAdmits++
        }
        assertTrue(earlyAdmits >= 40, "high-T admission rate too low: $earlyAdmits/50")

        cooling.reset()
        repeat(100) { cooling.admitsTabu(state, Move.BoolFlip(0)) }
        var lateAdmits = 0
        repeat(50) { if (cooling.admitsTabu(state, Move.BoolFlip(0))) lateAdmits++ }
        assertTrue(lateAdmits == 0, "low-T should never admit, got $lateAdmits/50")
    }

    @Test
    fun `Probabilistic aspiration admits tabu moves at the configured rate`() {
        val state = smallState()
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.Probabilistic(rate = 1.0))
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        val out = filter.filter(state, moves)
        assertEquals(2, out.size, "rate=1.0 should admit every tabu move; got $out")

        val zero = TabuFilter(tenure = 10, aspiration = AspirationCriterion.Probabilistic(rate = 0.0))
        val outZero = zero.filter(state, listOf<Move>(Move.BoolFlip(0)))
        assertEquals(listOf<Move>(Move.BoolFlip(0)), outZero)
    }

    @Test
    fun `Probabilistic aspiration rejects out-of-range rate at construction`() {
        try {
            AspirationCriterion.Probabilistic(rate = -0.1)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
        try {
            AspirationCriterion.Probabilistic(rate = 1.5)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `random band dynamic tenure stays within bounds`() {
        val fn = TabuFilter.randomBand(low = 5, high = 15, seed = 42L)
        repeat(100) {
            val t = fn(it.toLong())
            assertTrue(t in 5..15, "tenure $t escaped band [5,15]")
        }
    }

    @Test
    fun `linear growth dynamic tenure ramps from base to max`() {
        val fn = TabuFilter.linearGrowth(base = 5, max = 25, maxAtStep = 1000L)
        assertEquals(5, fn(0L))
        assertTrue(fn(500L) in 5..25, "tenure at midpoint should be in range")
        assertEquals(25, fn(1000L))
        assertEquals(25, fn(99999L), "tenure should saturate at max past maxAtStep")
    }

    @Test
    fun `walk sat with custom tabu filter still solves`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(
            problem,
            strategy = WalkSat(
                noise = 0.2,
                tabu = TabuFilter(tenure = 5, aspiration = AspirationCriterion.OrImproving),
            ),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100),
        )
        val sample = solver.sample().assignment
        assertTrue(sample != null, "WalkSat with TabuFilter failed to find a sample")
    }
}
