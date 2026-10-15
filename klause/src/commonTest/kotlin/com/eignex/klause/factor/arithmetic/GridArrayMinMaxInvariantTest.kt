package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GridArrayMinMaxInvariantTest {
    @Test
    fun `grid move deltas should preserve result and operand aliases`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf(
                GridArrayMinMax(
                    intArrayOf(0, 0, 1),
                    DoubleArray(3) { 1.0 },
                    DoubleArray(3),
                    BooleanArray(3),
                    1000L,
                    max = false,
                ),
            ),
        )
        val state = LocalSearchState(source.bake(), Random(0))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 1)
        state.recompute()
        val before = state.cost
        var predicted = 0
        state.forEachIntFactorDelta(0, 0) { _, delta -> predicted += delta }

        state.apply(Move.IntSet(0, 0))

        assertEquals(predicted.toLong(), state.cost - before)
        assertEquals(0L, state.cost)
    }
}
