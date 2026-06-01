package com.eignex.klause.solver.factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardinalityRepairTest {

    @Test
    fun `at most violated proposes only true literal flips`() {
        val a = 0
        val b = 1
        val c = 2
        val d = 3
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true), Lit.make(d, true)),
            min = 0,
            max = 2,
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (v in intArrayOf(a, b, c, d)) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()

        assertEquals(setOf(a, b, c, d), proposed)
    }

    @Test
    fun `at least violated proposes only false literal flips`() {
        val a = 0
        val b = 1
        val c = 2
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true), Lit.make(c, true)),
            min = 2,
            max = 3,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()

        assertEquals(setOf(b, c), proposed)
    }

    @Test
    fun `mixed polarity counts correctly`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, false)),
            min = 2,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
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
    fun `satisfied cardinality proposes nothing`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(b, true)),
            min = 1,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.recompute()
        assertTrue(!factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isEmpty())
    }
}
