package com.eignex.klause.factor.global

import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymmetricAllDifferentInvariantTest {

    // xs = vars 0..2, indexOffset = 0, so xs[xs[i]] == i required
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 2) },
        factors = arrayOf<Factor>(SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0)),
    )

    @Test
    fun `not violated for identity permutation`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[0,1,2]: xs[xs[i]]=i for all i
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `not violated for 2-cycle involution`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[1,0,2]: xs[1]=0, xs[0]=1 ✓; xs[2]=2 ✓
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `violated when xs is a 3-cycle`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[1,2,0]: xs[xs[0]]=xs[1]=2 ≠ 0 → violated
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta predicts degree change on corrective assignment`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
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
