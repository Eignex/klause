package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OccurrenceDedupTest {

    @Test
    fun `local search cost stays sound across a flip when a var appears twice in one factor`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false), Lit.make(b, true)),
            min = 1,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(7))
        state.recompute()
        val brute = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute, state.cost)
        state.apply(BoolFlip(a))
        val brute2 = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute2, state.cost, "cost drifted from brute-force after flipping a")
    }
}
