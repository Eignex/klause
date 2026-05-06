package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolverState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardinalityRepairTest {

    @Test
    fun atMostViolatedProposesOnlyTrueLiteralFlips() {
        // atMost(2) over [+a, +b, +c, +d]; all four currently true → count = 4 > max.
        val a = 0; val b = 1; val c = 2; val d = 3
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true), Lit.make(d, true)),
            min = 0, max = 2,
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        for (v in intArrayOf(a, b, c, d)) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        // All four vars are currently true; flipping any decreases count. All are valid.
        assertEquals(setOf(a, b, c, d), proposed)
    }

    @Test
    fun atLeastViolatedProposesOnlyFalseLiteralFlips() {
        // atLeast(2) over [+a, +b, +c]; only a is true (count=1 < min=2).
        val a = 0; val b = 1; val c = 2
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true)),
            min = 2, max = 3,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        // Want to increase count; only flips of currently-false literals (b, c) help.
        assertEquals(setOf(b, c), proposed)
    }

    @Test
    fun mixedPolarityCountsCorrectly() {
        // [+a, -b]; min=2, max=2 (exactly 2 trues required). With a=true, b=true →
        // +a true (1), -b false (0), count = 1 < min → wantIncrease.
        // Flipping a: would set +a false, count = 0 — wrong direction.
        // Flipping b: would set -b true, count = 2 — right direction. Only b should be proposed.
        val a = 0; val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, false)),
            min = 2, max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.assignment.setBool(b, true)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertEquals(setOf(b), proposed)
    }

    @Test
    fun satisfiedCardinalityProposesNothing() {
        val a = 0; val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true)),
            min = 1, max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(!factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isEmpty())
    }
}
