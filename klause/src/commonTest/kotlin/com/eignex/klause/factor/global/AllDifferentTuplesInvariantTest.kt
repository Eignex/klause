package com.eignex.klause.factor.global

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllDifferentTuplesInvariantTest {
    @Test
    fun `moves should update every row and repeated coordinate exactly once`() {
        val rows = listOf(intArrayOf(0, 0), intArrayOf(1, 2), intArrayOf(2, 0), intArrayOf(1, 1))
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(AllDifferentTuples(rows)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))

        for (bits in 0 until 8) {
            for (variable in 0 until 3) state.assignment.setInt(variable, ((bits shr variable) and 1).toLong())
            state.recompute()
            val expected = rows.size - rows.map { row -> row.map(state.assignment::intValue) }.distinct().size
            assertEquals(expected.toLong(), state.cost)
            for (variable in 0 until 3) {
                val value = 1 - state.assignment.intValue(variable)
                val move = Move.IntSet(variable, value)
                val after = rows.size - rows.map { row -> row.map {
                    if (it == variable) value else state.assignment.intValue(it)
                } }.distinct().size
                assertEquals((after - expected).toLong(), state.netDelta(move))
                assertEquals(expected.toLong(), state.cost)
                val old = state.assignment.intValue(variable)
                state.apply(move)
                assertEquals(after.toLong(), state.cost)
                state.apply(Move.IntSet(variable, old))
                assertEquals(expected.toLong(), state.cost)
            }
        }
    }

    @Test
    fun `compound exchanges should preserve tuple scores through rollback`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3)))))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (variable in 0 until 4) state.assignment.setInt(variable, (variable / 2).toLong())
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1), Move.IntSet(2, 0), Move.IntSet(3, 0)))

        assertEquals(0L, state.netDelta(move))
        assertEquals(0L, state.cost)
        state.apply(move)

        assertEquals(0L, state.cost)
    }

    @Test
    fun `repairs should propose in-domain moves away from duplicate tuples`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3)))))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (variable in 0 until 4) state.assignment.setInt(variable, 0)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        assertTrue(sink.list.isNotEmpty())
        for (move in sink.list) assertEquals(-1L, state.netDelta(move))
    }
}
