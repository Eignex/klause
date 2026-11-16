package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SortInvariantTest {

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 4) },
        factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
    )

    @Test
    fun `delta should predict degree change when correcting a ys entry`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 3, 0)
        state.apply(Move.IntSet(3, 0))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
