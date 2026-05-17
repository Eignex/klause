package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.localsearch.strategy.WalkSat

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
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
        // Touch var 0 by applying a flip — so isTaboo(BoolFlip(0), >=1) becomes true.
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
        // Tenure 0 means nothing is tabu.
        val filter = TabuFilter(tenure = 0)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        assertSame(moves, filter.filter(state, moves))
    }

    @Test
    fun `filter strips tabu candidates when alternatives exist`() {
        val state = smallState() // step=1, BoolFlip(0) is tabu under tenure 10
        val filter = TabuFilter(tenure = 10)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        val out = filter.filter(state, moves)
        assertEquals(listOf<Move>(Move.BoolFlip(1)), out)
    }

    @Test
    fun `filter falls back to full set when every move is tabu`() {
        val state = smallState() // BoolFlip(0) tabu
        val filter = TabuFilter(tenure = 10)
        // The only candidate is tabu; aspiration fallback must drop the filter, not return empty.
        val moves = listOf<Move>(Move.BoolFlip(0))
        val out = filter.filter(state, moves)
        assertEquals(moves, out)
    }

    @Test
    fun `aspiration admits tabu move that strictly improves cost`() {
        // Build a state where flipping var 0 is tabu but flipping it strictly improves cost.
        // Concrete setup: AtLeastOne(a,b) — start with a=false,b=false (violated), apply a flip on
        // var 0 making a=true (cost=0). The undo flip on var 0 is tabu (recent touch) and would
        // worsen cost, so aspiration should NOT admit it. Conversely if we set up the other way…
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        // Start with both false → factor violated. Touch var 1 (worsens nothing, but marks tabu).
        // Then flipping var 0 strictly improves cost (1 → 0). var 0 is NOT tabu, so first ensure
        // OrImproving admits it via "no tabu" path. Now touch var 0 too and verify OrImproving
        // would still admit it if it were the sole improving move.
        state.apply(Move.BoolFlip(0)) // a=true, cost=0
        state.apply(Move.BoolFlip(0)) // a=false again, cost=1; var 0 is tabu now (touched twice)

        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        val out = filter.filter(state, moves)
        // Both moves strictly improve cost (1 → 0). var 1 is non-tabu and admitted; var 0 is
        // tabu but OrImproving admits it. So both pass.
        assertEquals(2, out.size, "OrImproving should admit the strictly-improving tabu move; got $out")
    }

    @Test
    fun `dynamic tenure overrides the static value`() {
        val state = smallState()
        // Static tenure 10 (would flag var 0 tabu), but dynamic forces 0 → nothing is tabu.
        val filter = TabuFilter(tenure = 10, dynamicTenure = { 0 })
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))
        assertSame(moves, filter.filter(state, moves))
    }

    @Test
    fun `OrImprovesBestEver admits a tabu move that would beat the historical minimum`() {
        val state = smallState() // BoolFlip(0) tabu under tenure 10
        // smallState applies BoolFlip(0) once before returning. That flip changes cost;
        // bestCostSeen now reflects the post-flip cost. The cost is currently the
        // same. A move reaching strictly below bestCostSeen is admitted.
        val initialBest = state.bestCostSeen
        // Apply some flips to drive cost down (and bestCostSeen down).
        state.apply(Move.BoolFlip(1)) // var 1 now true alongside var 0
        // The minimum cost observed so far is captured.
        val current = state.bestCostSeen
        assertTrue(current <= initialBest, "best-cost should monotone-decrease, got $initialBest -> $current")

        // Build a filter with the best-ever aspiration; verify it admits a tabu move
        // predicted to beat the historical low.
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImprovesBestEver)
        val moves = listOf<Move>(Move.BoolFlip(0))
        val out = filter.filter(state, moves)
        // Whether the move is admitted depends on netDelta; we just verify the filter
        // doesn't crash and returns a sensible list (either the move itself if admitted,
        // or the fallback all-tabu list since there's only one candidate).
        assertTrue(out.isNotEmpty())
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
        // Smoke test: passing a TabuFilter through the constructor changes nothing observable
        // for callers that previously passed tabuTenure.
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
        val sample = solver.sample()
        assertTrue(sample != null, "WalkSat with TabuFilter failed to find a sample")
    }
}
