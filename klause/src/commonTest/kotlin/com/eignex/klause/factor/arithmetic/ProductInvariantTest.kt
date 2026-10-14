package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductInvariantTest {
    @Test
    fun `predicted alias moves agree with whole factor grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10) },
                arrayOf<Factor>(Product(a, b, result)))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for ((old, target) in listOf(2L to 3L, -2L to -3L, 0L to 1L, 1L to 0L)) {
                state.assignment.setInt(0, old)
                state.assignment.setInt(1, 4)
                state.recompute()
                val before = state.cost
                val predicted = state.netDelta(Move.IntSet(0, target))
                state.assignment.setInt(0, target)
                state.recompute()

                assertEquals(state.cost - before, predicted, "$roles $old -> $target")
            }
        }
    }

    @Test
    fun `committed alias moves agree with whole factor grading`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            val (a, b, result) = roles
            val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10) },
                arrayOf<Factor>(Product(a, b, result)))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for ((old, target) in listOf(2L to 3L, -2L to -3L, 0L to 1L, 1L to 0L)) {
                state.assignment.setInt(0, old)
                state.assignment.setInt(1, 4)
                state.recompute()

                state.apply(Move.IntSet(0, target))
                val committed = state.cost
                state.recompute()

                assertEquals(state.cost, committed, "$roles $old -> $target")
            }
        }
    }

    @Test
    fun `alias repair candidates do not increase the whole residual`() {
        for (roles in listOf(Triple(0, 0, 1), Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, 0))) {
            for (other in listOf(3L, 5L, 8L)) {
                val (a, b, result) = roles
                val problem = Problem(0, 2, Array(2) { IntDomain(-10, 10).excludeValue(7) },
                    arrayOf<Factor>(Product(a, b, result)))
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
                state.assignment.setInt(0, 2)
                state.assignment.setInt(1, other)
                state.recompute()
                val before = state.cost

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val moves = state.moveSink.list

                assertTrue(moves.isNotEmpty())
                for (move in moves) {
                    val candidate = move as Move.IntSet
                    assertTrue(candidate.newValue in state.rootDomains[candidate.varId])
                    state.assignment.setInt(candidate.varId, candidate.newValue)
                    state.recompute()
                    assertTrue(state.cost <= before, "$roles $other $move")
                    state.assignment.setInt(0, 2)
                    state.assignment.setInt(1, other)
                    state.recompute()
                }
            }
        }
    }
}
