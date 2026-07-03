package com.eignex.klause.factor.bool

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XorInvariantTest {

    @Test
    fun `violated when current parity differs from odd target`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, false) // parity = 0 != 1
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `satisfied when parity matches odd target`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false) // parity = 1
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta is negative when flip fixes violation`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, false) // violated
        state.recompute()
        val delta = state.factors[0].deltaIfBoolFlipped(state, 0, 0)
        assertEquals(-1, delta)
    }

    @Test
    fun `delta is positive when flip breaks satisfied constraint`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false) // parity=1, satisfied
        state.recompute()
        val delta = state.factors[0].deltaIfBoolFlipped(state, 0, 0)
        assertEquals(1, delta)
    }

    @Test
    fun `repair proposes all parity-contributing vars when violated`() {
        val factor = Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
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
        val state = LocalSearchState(problem, Random(0))
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
