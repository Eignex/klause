package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RealElementInvariantTest {
    @Test
    fun `real move deltas should account for an entry aliasing the result`() {
        for ((index, proposed) in listOf(1L to 1.0, 2L to 3.0)) {
            val source = Problem(
                0,
                1,
                arrayOf(IntDomain(1, 2)),
                arrayOf<Factor>(RealElement(0, 1, intArrayOf(0, 1))),
                numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0),
                realUpper = doubleArrayOf(4.0, 4.0),
            )
            val state = LocalSearchState(source.bake(), Random(0))
            state.assignment.setInt(0, index)
            state.assignment.setReal(0, 1.0)
            state.assignment.setReal(1, 2.0)
            state.recompute()
            val before = state.cost
            var predicted = 0
            state.forEachRealFactorDelta(1, proposed) { _, delta -> predicted += delta }

            state.apply(Move.RealSet(1, proposed))

            assertEquals(predicted.toLong(), state.cost - before)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `index move deltas should select the corresponding real entry`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 2)),
            arrayOf<Factor>(RealElement(0, 0, doubleArrayOf(1.0, 2.0))),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )
        val state = LocalSearchState(source.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setReal(0, 2.0)
        state.recompute()
        val before = state.cost
        var predicted = 0
        state.forEachIntFactorDelta(0, 2) { _, delta -> predicted += delta }

        state.apply(Move.IntSet(0, 2))

        assertEquals(predicted.toLong(), state.cost - before)
        assertEquals(0L, state.cost)
    }
}
