package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RealAbsInvariantTest {
    @Test
    fun `real absolute value deltas should replace every aliased source role`() {
        for (result in listOf(0, 1)) {
            val source = Problem(
                0,
                0,
                emptyArray(),
                arrayOf(RealAbs(0, result)),
                numRealVars = 2,
                realLower = doubleArrayOf(-4.0, -4.0),
                realUpper = doubleArrayOf(4.0, 4.0),
            )
            val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
            for (old in listOf(-3.0, 0.0, 3.0)) {
                for (target in listOf(-2.0, 0.0, 2.0)) {
                    state.assignment.setReal(0, old)
                    state.assignment.setReal(1, 2.0)
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
    }
}
