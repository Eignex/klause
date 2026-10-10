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

class SymmetricAllDifferentInvariantTest {

    // xs = vars 0..2, indexOffset = 0, so xs[xs[i]] == i required
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 2) },
        factors = arrayOf<Factor>(SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0)),
    )

    @Test
    fun `delta predicts degree change on corrective assignment`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        // xs=[1,2,0] violated
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        // Change xs[2]=0 → xs[2]=2 (makes xs[2]=2 so xs[xs[2]]=xs[2]=2 ✓, and xs[1]=2 → xs[xs[1]]=xs[2])
        val delta = state.factors[0].deltaIfIntSet(state, 0, 2, 2)
        state.apply(Move.IntSet(2, 2))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
