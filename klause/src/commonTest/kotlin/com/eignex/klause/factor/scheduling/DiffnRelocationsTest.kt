package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiffnRelocationsTest {
    @Test
    fun `default cbls improves a packing envelope among arithmetic rows`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val rows = listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 4), LinearOp.LE, -2),
            Linear(longArrayOf(1, -1), intArrayOf(1, 4), LinearOp.LE, -3),
        ) + List(256) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 12) }
        val domains = arrayOf(IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 0), IntDomain(0, 0),
            IntDomain(0, 20))
        val state = LocalSearchState(LocalSearchModel.open(Problem(0, 5, domains, listOf(factor) + rows)), Random(43))
        val values = longArrayOf(5, 10, 0, 0, 13)
        values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
        state.recompute()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 1))
        state.shaping.objective = objective

        val move = assertNotNull(Cbls().pickMove(state))

        assertTrue(checkNotNull(state.objectiveDelta(objective, move)) < 0.0)
        assertEquals(0L, state.netDelta(move))
        state.apply(move)
        state.recompute()
        assertEquals(0L, state.cost)
        assertTrue(state.assignment.intValue(4) < 13L)
    }

    @Test
    fun `relocations reconcile exact source lower envelopes`() {
        for (wide in listOf(false, true)) {
            val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
            val rows = listOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 4), LinearOp.LE, -2),
                if (wide) {
                    Linear(longArrayOf(Long.MAX_VALUE, -Long.MAX_VALUE, 1, -1),
                        intArrayOf(5, 6, 1, 4), LinearOp.LE, -3)
                } else {
                    Linear(longArrayOf(1, -1), intArrayOf(1, 4), LinearOp.LE, -3)
                },
            )
            val domains = arrayOf(IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 0), IntDomain(0, 0),
                IntDomain(0, 20), IntDomain(2, 2), IntDomain(2, 2))
            val problem = Problem(0, 7, domains, listOf(factor) + rows)
            val state = LocalSearchState(LocalSearchModel.open(problem, domains), Random(43))
            val values = longArrayOf(5, 10, 0, 0, 13, 2, 2)
            values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
            state.recompute()
            val sink = MoveSink()

            repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

            val joint = sink.list.filterIsInstance<Move.Compound>().first {
                it.parts.toSet() == setOf(Move.IntSet(1, 2), Move.IntSet(4, 7))
            }
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 1, 0, 0))
            assertEquals(-6.0, state.objectiveDelta(objective, joint))
            assertEquals(0L, state.netDelta(joint))
            state.apply(joint)
            state.recompute()
            assertEquals(0L, state.cost)
            assertEquals(7L, state.assignment.intValue(4))
        }
    }

    @Test
    fun `source lower envelope targets respect holes and pins`() {
        for (pinned in listOf(false, true)) {
            val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
            val rows = listOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 4), LinearOp.LE, -2),
                Linear(longArrayOf(1, -1), intArrayOf(1, 4), LinearOp.LE, -3),
            )
            val domains = arrayOf(IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 0), IntDomain(0, 0),
                IntDomain(0, 20).excludeValue(7))
            val problem = Problem(0, 5, domains, listOf(factor) + rows)
            val assumptions = Assumptions(ints = if (pinned) mapOf(4 to 13L) else emptyMap())
            val state = LocalSearchState(LocalSearchModel.open(problem, domains), Random(47), assumptions)
            val values = longArrayOf(5, 10, 0, 0, 13)
            values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
            state.recompute()
            val sink = MoveSink(assumptions)

            repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

            if (pinned) {
                assertTrue(Move.IntSet(1, 2) in sink.list)
            } else {
                val joint = sink.list.filterIsInstance<Move.Compound>().first {
                    it.parts.toSet() == setOf(Move.IntSet(1, 2), Move.IntSet(4, 8))
                }
                assertEquals(0L, state.netDelta(joint))
            }
        }
    }

    @Test
    fun `heterogeneous rectangles compact into free source positions`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val domains = arrayOf(IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 0), IntDomain(0, 0))
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor)).bake(), Random(7))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 10)
        state.recompute()
        val sink = MoveSink()

        repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        assertTrue(Move.IntSet(0, 0) in sink.list)
        for (move in sink.list) {
            assertEquals(0L, state.netDelta(move))
            assertEquals(5L, state.assignment.intValue(0))
            assertEquals(10L, state.assignment.intValue(1))
        }
    }

    @Test
    fun `joint heterogeneous reorder preserves packing when either write overlaps`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val domains = arrayOf(IntDomain(0, 3), IntDomain(0, 2), IntDomain(0, 0), IntDomain(0, 0))
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor)).bake(), Random(13))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.recompute()
        val sink = MoveSink()

        repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        val joint = sink.list.filterIsInstance<Move.Compound>().first {
            it.parts.toSet() == setOf(Move.IntSet(0, 3), Move.IntSet(1, 0))
        }
        assertTrue(state.netDelta(Move.IntSet(0, 3)) > 0)
        assertTrue(state.netDelta(Move.IntSet(1, 0)) > 0)
        assertEquals(0L, state.netDelta(joint))
        state.apply(joint)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `joint compaction uses each rectangles source lower bound`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val domains = arrayOf(IntDomain(0, 10), IntDomain(11, 12), IntDomain(0, 0), IntDomain(0, 0))
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor)).bake(), Random(37))
        state.assignment.setInt(0, 10)
        state.assignment.setInt(1, 12)
        state.recompute()
        val sink = MoveSink()

        repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        val joint = sink.list.filterIsInstance<Move.Compound>().first {
            it.parts.toSet() == setOf(Move.IntSet(0, 9), Move.IntSet(1, 11))
        }
        assertTrue(state.netDelta(Move.IntSet(1, 11)) > 0)
        assertEquals(0L, state.netDelta(joint))
        state.apply(joint)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `source holes and protected coordinates retain complete relocation targets`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val domains = arrayOf(IntDomain(0, 4).excludeValue(3), IntDomain(0, 2), IntDomain(0, 0), IntDomain(0, 0))
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor)).bake(), Random(17))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.recompute()
        for (protected in listOf(false, true)) {
            val sink = MoveSink(Assumptions(ints = if (protected) mapOf(1 to 2L) else emptyMap()))
            if (!protected) sink.setOwners(intArrayOf(-1, 1, -1, -1))

            repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

            assertTrue(sink.list.isEmpty())
        }
    }

    @Test
    fun `aliased position and dimension roles use the final joint footprint`() {
        for (nonStrict in listOf(false, true)) {
            val factor = Diffn(
                intArrayOf(0, 1, 0),
                intArrayOf(2, 3, 4),
                LongArray(3),
                longArrayOf(1, 2, 0),
                widthVars = intArrayOf(1, 5, 5),
                nonStrict = nonStrict,
            )
            val domains = Array(6) { IntDomain(0, 10) }
            val state = LocalSearchState(Problem(0, 6, domains, listOf(factor)).bake(), Random(19))
            val values = longArrayOf(0, 3, 0, 0, 4, 2)
            values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
            state.recompute()
            val sink = MoveSink()

            repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

            assertTrue(sink.list.isNotEmpty())
            for (move in sink.list) {
                values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
                state.recompute()
                state.apply(move)
                state.recompute()
                assertEquals(0L, state.cost, "$nonStrict: $move")
            }
        }
    }

    @Test
    fun `packing proposals still grade other model constraints`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 3), longArrayOf(1, 2))
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 12)
        val domains = arrayOf(IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 0), IntDomain(0, 0))
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor, row)).bake(), Random(23))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 10)
        state.recompute()
        val sink = MoveSink()

        repeat(8) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        val relocation = sink.list.first { it == Move.IntSet(0, 0) }
        assertTrue(state.netDelta(relocation) > 0)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `zero width relocations preserve the source strictness`() {
        for (nonStrict in listOf(false, true)) {
            val factor = Diffn(
                intArrayOf(0, 1, 2),
                intArrayOf(3, 4, 5),
                longArrayOf(0, 3, 1),
                longArrayOf(1, 2, 1),
                nonStrict = nonStrict,
            )
            val domains = arrayOf(
                IntDomain(0, 4),
                IntDomain(0, 0),
                IntDomain(1, 1),
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(4, 4),
            )
            val problem = Problem(0, 6, domains, listOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem, domains), Random(29))
            val values = longArrayOf(4, 0, 1, 0, 0, 4)
            values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
            state.recompute()
            val sink = MoveSink()

            repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

            assertEquals(nonStrict, Move.IntSet(0, 1) in sink.list)
        }
    }

    @Test
    fun `overflowing adjacency endpoints do not wrap into source positions`() {
        val factor = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(2, 4), longArrayOf(1, 2))
        val domains = arrayOf(
            IntDomain(Long.MIN_VALUE + 1, Long.MAX_VALUE - 1),
            IntDomain(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1),
            IntDomain(0, 0),
            IntDomain(5, 5),
        )
        val state = LocalSearchState(Problem(0, 4, domains, listOf(factor)).bake(), Random(31))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, Long.MAX_VALUE - 1)
        state.recompute()
        val sink = MoveSink()

        repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        assertTrue(sink.list.isNotEmpty())
        assertTrue(Move.IntSet(0, Long.MIN_VALUE + 2) !in sink.list)
    }

    @Test
    fun `overflowing footprints do not appear adjacent at the largest coordinate`() {
        val factor = Diffn(
            intArrayOf(0, 1, 2),
            intArrayOf(3, 4, 5),
            longArrayOf(2, 1, 1),
            longArrayOf(1, 1, 1),
        )
        val domains = arrayOf(
            IntDomain(Long.MAX_VALUE - 5, Long.MAX_VALUE),
            IntDomain(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1),
            IntDomain(Long.MAX_VALUE, Long.MAX_VALUE),
            IntDomain(0, 0),
            IntDomain(0, 0),
            IntDomain(0, 0),
        )
        val problem = Problem(0, 6, domains, listOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem, domains), Random(41))
        val values = longArrayOf(Long.MAX_VALUE - 5, Long.MAX_VALUE - 1, Long.MAX_VALUE, 0, 0, 0)
        values.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
        state.recompute()
        val sink = MoveSink()

        repeat(16) { state.factors[0].proposeStructuredMoves(state, 0, sink) }

        assertTrue(Move.IntSet(0, Long.MAX_VALUE - 3) in sink.list)
        assertTrue(Move.IntSet(0, Long.MAX_VALUE) !in sink.list)
    }
}
