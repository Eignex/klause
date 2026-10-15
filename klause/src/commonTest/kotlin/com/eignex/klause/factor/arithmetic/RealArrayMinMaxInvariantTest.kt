package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RealArrayMinMaxInvariantTest {
    @Test
    fun `real move deltas should preserve repeated operand and result aliases`() {
        for (max in listOf(false, true)) {
            val source = Problem(
                0,
                0,
                emptyArray(),
                arrayOf<Factor>(RealArrayMinMax(0, intArrayOf(0, 1, 0), max)),
                numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0),
                realUpper = doubleArrayOf(4.0, 4.0),
            )
            val state = LocalSearchState(LocalSearchModel.open(source), Random(0))
            state.assignment.setReal(0, if (max) 1.0 else 3.0)
            state.assignment.setReal(1, 2.0)
            state.recompute()
            val before = state.cost
            val proposed = if (max) 3.0 else 1.0
            var predicted = 0
            state.forEachRealFactorDelta(0, proposed) { _, delta -> predicted += delta }

            state.apply(Move.RealSet(0, proposed))

            assertEquals(predicted.toLong(), state.cost - before)
            assertEquals(0L, state.cost)
        }
    }
}
