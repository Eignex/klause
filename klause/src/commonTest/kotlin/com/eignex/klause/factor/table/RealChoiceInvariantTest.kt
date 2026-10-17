package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealChoiceInvariantTest {
    @Test
    fun `real move deltas should preserve signed choices and result aliases`() {
        for (positive in listOf(false, true)) {
            for (truth in listOf(false, true)) {
                val source = Problem(
                    1,
                    0,
                    emptyArray(),
                    arrayOf<Factor>(RealChoice(Lit.make(0, positive), 1, 0, 1)),
                    numRealVars = 2,
                    realLower = doubleArrayOf(0.0, 0.0),
                    realUpper = doubleArrayOf(4.0, 4.0),
                )
                val state = LocalSearchState(LocalSearchModel.open(source), Random(0))
                state.assignment.setBool(0, truth)
                state.assignment.setReal(0, 1.0)
                state.assignment.setReal(1, 2.0)
                state.recompute()
                val before = state.cost
                var predicted = 0
                state.forEachRealFactorDelta(1, 1.0) { _, delta -> predicted += delta }

                state.apply(Move.RealSet(1, 1.0))

                assertEquals(predicted.toLong(), state.cost - before)
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `Boolean move deltas should preserve the source literal polarity`() {
        for (positive in listOf(false, true)) {
            val source = Problem(
                1,
                0,
                emptyArray(),
                arrayOf<Factor>(RealChoice(Lit.make(0, positive), 0, 1, 2)),
                numRealVars = 3,
                realLower = doubleArrayOf(0.0, 0.0, 0.0),
                realUpper = doubleArrayOf(4.0, 4.0, 4.0),
            )
            val state = LocalSearchState(LocalSearchModel.open(source), Random(0))
            state.assignment.setBool(0, positive)
            state.assignment.setReal(0, 2.0)
            state.assignment.setReal(1, 1.0)
            state.assignment.setReal(2, 2.0)
            state.recompute()
            val before = state.cost
            var predicted = 0
            state.forEachBoolFactorDelta(0) { _, delta -> predicted += delta }

            state.apply(Move.BoolFlip(0))

            assertEquals(predicted.toLong(), state.cost - before)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `repair proposals should retain guard and real coordinate alternatives`() {
        val factor = RealChoice(Lit.make(0, true), 0, 1, 2)
        val source = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(factor),
            numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.0, 0.0),
            realUpper = doubleArrayOf(4.0, 4.0, 4.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(source), Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setReal(0, 2.0)
        state.assignment.setReal(1, 1.0)
        state.assignment.setReal(2, 2.0)
        state.recompute()
        val moves = MoveSink()

        RealChoiceInvariant(factor).proposeRepairMoves(state, 0, moves)

        assertTrue(Move.BoolFlip(0) in moves.list)
        assertTrue(Move.RealSet(0, 1.0) in moves.list)
        assertTrue(Move.RealSet(1, 2.0) in moves.list)
    }
}
