package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbsInvariantTest {
    @Test
    fun `aliased move deltas agree with complete source grading`() {
        for (result in listOf(0, 1)) {
            val source = Problem(0, 2, Array(2) { IntDomain(-4, 4) }, arrayOf(Abs(0, result)))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            for (old in -3L..3L) {
                for (target in -3L..3L) {
                state.assignment.setInt(0, old)
                state.assignment.setInt(1, 2L)
                state.recompute()
                val before = state.cost
                val predicted = state.netDelta(Move.IntSet(0, target))
                state.assignment.setInt(0, target)
                val applied = state.factors[0].applyIntSet(state, 0, 0, old)
                state.recompute()

                assertEquals(state.cost - before, predicted)
                assertEquals(predicted, applied.toLong())
            }
            }
        }
    }

    @Test
    fun `repairs respect domain holes and reduce the complete aliased residual`() {
        for (result in listOf(0, 1)) {
            val source = Problem(0, 2, Array(2) { IntDomain(-4, 4).excludeValue(2) }, arrayOf(Abs(0, result)))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            state.assignment.setInt(0, -3L)
            state.assignment.setInt(1, 4L)
            state.recompute()

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.isNotEmpty())
            for (move in state.moveSink.list) {
                val candidate = move as Move.IntSet
                assertTrue(candidate.newValue in state.rootDomains[candidate.varId])
                assertTrue(state.netDelta(candidate) <= 0L)
            }
        }
    }

    @Test
    fun `minimum signed operand never receives a zero violation`() {
        val source = Problem(0, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) }, arrayOf(Abs(0, 1)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setInt(0, Long.MIN_VALUE)
        state.assignment.setInt(1, Long.MIN_VALUE)
        state.recompute()

        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.cost > 0L)
    }
}
