package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class RealProductInvariantTest {
    @Test
    fun `overflowing mixed product should never receive zero numerical violation`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(2, 2)),
            arrayOf(RealProduct(0, 0, 1, 1.0, Double.MAX_VALUE)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0),
            realUpper = doubleArrayOf(Double.MAX_VALUE, 1.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setInt(0, 2)
        state.assignment.setReal(0, Double.MAX_VALUE)
        state.assignment.setReal(1, 1.0)

        state.recompute()

        assertTrue(state.cost > 0)
    }
}
