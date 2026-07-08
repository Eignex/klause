package com.eignex.klause.factor.bool

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoBooleanInvariantTest {

    @Test
    fun `LE violated when weighted sum exceeds bound`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 4L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, true) // sum = 6 > 4
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `LE violated for weights and bound beyond Int range`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3_000_000_000L, 3_000_000_000L),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 5_000_000_000L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..1) state.assignment.setBool(v, true) // sum = 6e9 > 5e9
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `LE satisfied when sum equals bound`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 6L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, true) // sum = 6 <= 6
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `GE violated when sum below bound`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.GE,
            bound = 4L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false) // sum = 2 < 4
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta is positive when flip pushes LE sum above bound`() {
        // weights=[3,2,1], bound=4. v0=true, rest false → sum=3 satisfied.
        // Flipping v1 true: sum→5 > 4 → delta > 0.
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 4L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfBoolFlipped(state, 0, 1)
        assertTrue(delta > 0, "flipping v1 to true should push sum above LE bound; delta=$delta")
    }

    @Test
    fun `apply eliminates GE violation and zeroes cost`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.GE,
            bound = 4L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false) // sum=2 < 4 → violated
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(1)) // sum → 5 >= 4
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.cost)
    }

    @Test
    fun `repair proposes only flips that reduce distance`() {
        // weights=[3,2,1], bound=4. All true → sum=6 > 4. Flipping any var to false reduces sum.
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 4L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertTrue(proposed.isNotEmpty(), "repair should propose flips when LE violated")
        assertTrue(proposed.all { v -> state.assignment.boolValue(v) }, "should propose true→false flips to reduce sum")
    }
}
