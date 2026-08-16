package com.eignex.klause.factor.global

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
    fun `not violated for an involution`() {
        // xs[xs[i]]=i holds for the identity and for a 2-cycle with one fixed point.
        for (xs in listOf(listOf(0L, 1L, 2L), listOf(1L, 0L, 2L))) {
            val state = LocalSearchState(problem(), Random(0))
            for (i in 0..2) state.assignment.setInt(i, xs[i])
            state.recompute()
            assertFalse(state.factors[0].isViolated(state, 0), "xs=$xs is an involution")
            assertEquals(0, state.factors[0].violationDegree(state, 0), "xs=$xs")
        }
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
