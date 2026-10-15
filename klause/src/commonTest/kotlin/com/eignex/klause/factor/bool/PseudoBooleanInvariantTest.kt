package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoBooleanInvariantTest {

    @Test
    fun `LE row should be violated for weights and bound beyond Int range`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3_000_000_000L, 3_000_000_000L),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 5_000_000_000L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..1) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `apply should eliminate a GE violation and zero the cost`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.GE,
            bound = 4L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(1))
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.cost)
    }

    @Test
    fun `repair should propose only flips that reduce distance`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 4L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..2) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertTrue(proposed.isNotEmpty())
        assertTrue(proposed.all { v -> state.assignment.boolValue(v) }, "$proposed")
    }
}
