package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealPowerInvariantTest {
    @Test
    fun `power move deltas retain exponent and aliased result roles`() {
        for (exponent in listOf(0, 1, 2, 3)) for (result in listOf(0, 1)) {
            val factor = Power(Power.Coordinate.Real(SourceRealId(0)), exponent,
                Power.Coordinate.Real(SourceRealId(result)))
            val source = Problem(0, 0, emptyArray(), arrayOf(factor), numRealVars = 2,
                realLower = doubleArrayOf(-10.0, -10.0), realUpper = doubleArrayOf(10.0, 10.0))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            for ((old, target) in listOf(-2.0 to 2.0, 0.0 to 1.0, 1.0 to 0.0)) {
                state.assignment.setReal(0, old)
                state.assignment.setReal(1, 4.0)
                state.recompute()
                val before = state.cost
                val predicted = state.netDelta(Move.RealSet(0, target))
                state.assignment.setReal(0, target)
                val applied = state.factors[0].applyRealSet(state, 0, 0, old)
                state.recompute()

                assertEquals(state.cost - before, predicted)
                assertEquals(predicted, applied.toLong())
            }
        }
    }

    @Test
    fun `power inverse repairs retain negative odd roots and both even roots`() {
        for ((exponent, output, roots) in listOf(Triple(2, 4.0, setOf(-2.0, 2.0)),
            Triple(3, -8.0, setOf(-2.0)))) {
            val factor = Power(Power.Coordinate.Real(SourceRealId(0)), exponent,
                Power.Coordinate.Real(SourceRealId(1)))
            val source = Problem(0, 0, emptyArray(), arrayOf(factor), numRealVars = 2,
                realLower = doubleArrayOf(-3.0, output), realUpper = doubleArrayOf(3.0, output))
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            state.assignment.setReal(0, 1.0)
            state.assignment.setReal(1, output)
            state.recompute()
            val sink = MoveSink()

            state.factors[0].proposeRepairMoves(state, 0, sink)

            assertEquals(roots.map { Move.RealSet(0, it) }.toSet(), sink.list.toSet())
            assertTrue(sink.list.all { state.netDelta(it) < 0 })
        }
    }
}
