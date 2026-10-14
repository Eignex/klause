package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
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
    fun `aspiration admits a feasible objective improvement despite a recent write`() {
        val state = smallState()
        state.assignment.setBool(1, true)
        state.recompute()
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(1, 0))
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))

        val out = filter.filter(state, moves)

        assertEquals(moves, out)
    }

    @Test
    fun `objective aspiration rejects moves that break feasibility`() {
        val state = smallState()
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(1, 0))
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))

        val out = filter.filter(state, moves)

        assertEquals(listOf<Move>(Move.BoolFlip(1)), out)
    }

    @Test
    fun `objective aspiration rejects unchanged and worsening objectives`() {
        for (coefficient in longArrayOf(0, -1)) {
            val state = smallState()
            state.assignment.setBool(1, true)
            state.recompute()
            state.shaping.objective = LinearObjective(boolWeights = longArrayOf(coefficient, 0))
            val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
            val moves = listOf<Move>(Move.BoolFlip(0), Move.BoolFlip(1))

            val out = filter.filter(state, moves)

            assertEquals(listOf<Move>(Move.BoolFlip(1)), out)
        }
    }

    @Test
    fun `objective aspiration rejects wide worsening integer changes`() {
        val half = 1L shl 62
        for ((coefficient, before, after) in listOf(
            Triple(1L, -half, half),
            Triple(Long.MAX_VALUE, 0L, 2L),
        )) {
            val problem = Problem(1, 1, arrayOf(IntDomain(before, after)), emptyArray())
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setInt(0, before)
            state.recompute()
            state.apply(Move.IntSet(0, after))
            state.apply(Move.IntSet(0, before))
            state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(coefficient))
            val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
            val alternative = Move.BoolFlip(0)

            val out = filter.filter(state, listOf(Move.IntSet(0, after), alternative))

            assertEquals(listOf<Move>(alternative), out)
        }
    }

    @Test
    fun `objective aspiration rejects negating the smallest Boolean weight`() {
        val state = smallState()
        state.assignment.setBool(1, true)
        state.recompute()
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(Long.MIN_VALUE, 0))
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

        val out = filter.filter(state, listOf(Move.BoolFlip(0), Move.BoolFlip(1)))

        assertEquals(listOf<Move>(Move.BoolFlip(1)), out)
    }

    @Test
    fun `objective aspiration retains unit improvements after wide compound cancellation`() {
        val half = 1L shl 62
        val problem = Problem(1, 2, arrayOf(IntDomain(0L, half), IntDomain(-half - 1L, 0L)), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.recompute()
        state.apply(Move.IntSet(0, 1))
        state.apply(Move.IntSet(0, 0))
        state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val improving = Move.Compound(listOf(Move.IntSet(0, half), Move.IntSet(1, -half - 1)))
        val moves = listOf<Move>(improving, Move.BoolFlip(0))

        val out = filter.filter(state, moves)

        assertEquals(moves, out)
        assertEquals(-1.0, state.objectiveDelta(checkNotNull(state.shaping.objective), improving))
    }

    @Test
    fun `objective aspiration rejects exact zero after wide compound cancellation`() {
        val half = 1L shl 53
        val state = LocalSearchState(LocalSearchModel.open(Problem(4, 0, emptyArray(), emptyArray())), Random(0))
        state.recompute()
        state.apply(Move.BoolFlip(0))
        state.apply(Move.BoolFlip(0))
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(half + 1, -half, -1, 0))
        val filter = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val unchanged = Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(2)))
        val alternative = Move.BoolFlip(3)

        val out = filter.filter(state, listOf(unchanged, alternative))

        assertEquals(listOf<Move>(alternative), out)
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
