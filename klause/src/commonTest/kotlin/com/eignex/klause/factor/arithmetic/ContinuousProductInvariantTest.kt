package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinuousProductInvariantTest {
    @Test
    fun `product move deltas replace every aliased real role`() {
        for ((a, b, result) in listOf(Triple(0, 1, 2), Triple(0, 0, 1), Triple(0, 1, 0),
            Triple(1, 0, 0), Triple(0, 0, 0))) {
            val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(a, b, result)), numRealVars = 3,
                realLower = DoubleArray(3) { -10.0 }, realUpper = DoubleArray(3) { 10.0 })
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            for ((old, target) in listOf(-3.0 to 2.0, 0.0 to 1.0, 1.0 to 0.0)) {
                state.assignment.setReal(0, old)
                state.assignment.setReal(1, 4.0)
                state.assignment.setReal(2, 6.0)
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
    fun `finite choice guard flips agree with complete numerical regrading`() {
        val factor = ContinuousProduct(0, 0, 1, 0, doubleArrayOf(0.125, 0.375),
            intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        val source = Problem(1, 0, emptyArray(), arrayOf(factor), numRealVars = 2,
            realLower = DoubleArray(2), realUpper = DoubleArray(2) { 1.0 })
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        for (value in listOf(0.125, 0.375)) {
            state.assignment.setReal(0, value)
            state.assignment.setReal(1, value * value)
            state.assignment.setBool(0, true)
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(Move.BoolFlip(0))
            state.assignment.setBool(0, false)
            val applied = state.factors[0].applyBoolFlip(state, 0, 0)
            state.recompute()

            assertEquals(state.cost - before, predicted)
            assertEquals(predicted, applied.toLong())
        }
    }

    @Test
    fun `square inverse repairs retain both signs within declared bounds`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-3.0, 4.0), realUpper = doubleArrayOf(3.0, 4.0))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setReal(0, 1.0)
        state.assignment.setReal(1, 4.0)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        assertEquals(setOf(Move.RealSet(0, -2.0), Move.RealSet(0, 2.0)), sink.list.toSet())
        assertTrue(sink.list.all { state.netDelta(it) < 0 })
    }

    @Test
    fun `overflowing product never receives zero numerical violation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(Double.MAX_VALUE, 1.0))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setReal(0, Double.MAX_VALUE)
        state.assignment.setReal(1, 1.0)

        state.recompute()

        assertTrue(state.cost > 0)
    }
}
