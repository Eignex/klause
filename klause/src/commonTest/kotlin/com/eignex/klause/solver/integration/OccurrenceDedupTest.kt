package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OccurrenceDedupTest {

    @Test
    fun `cardinality with same var twice dedups occurrence list`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false), Lit.make(b, true)),
            min = 1,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val occA = problem.boolOccurrences[a]
        assertEquals(1, occA.size, "var a registered ${occA.size} times in occurrence list (expected 1)")
    }

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
        val state = LocalSearchState(problem, Random(7))
        state.recompute()
        val brute = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute, state.cost)
        state.apply(BoolFlip(a))
        val brute2 = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute2, state.cost, "cost drifted from brute-force after flipping a")
    }

    @Test
    fun `clause with same var twice dedups occurrence list`() {
        val a = 0
        val factor = Clause(literals = intArrayOf(Lit.make(a, true), Lit.make(a, false)))
        val problem = Problem(1, 0, emptyArray(), listOf(factor))
        assertEquals(1, problem.boolOccurrences[a].size)
    }

    @Test
    fun `pseudo boolean with same var twice dedups occurrence list`() {
        val a = 0
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false)),
            op = PbOp.LE,
            bound = 4,
        )
        val problem = Problem(1, 0, emptyArray(), listOf(factor))
        assertEquals(1, problem.boolOccurrences[a].size)
    }
}
