package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XorInvariantTest {

    @Test
    fun `repair proposes all parity-contributing vars when violated`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..2) state.assignment.setBool(v, false)
        state.recompute()
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertEquals(setOf(0, 1, 2), proposed)
    }

    @Test
    fun `var with even occurrence count has zero delta and no repair proposal`() {
        // v0 appears twice -> contribution = 0; v1 appears once -> contribution = 1
        val factor = Xor(
            intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(1, true)),
            targetParity = 1,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..1) state.assignment.setBool(v, false)
        state.recompute()
        assertEquals(0, state.factors[0].deltaIfBoolFlipped(state, 0, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertFalse(0 in proposed, "v0 has zero parity contribution and should not be proposed")
        assertTrue(1 in proposed)
    }
}
