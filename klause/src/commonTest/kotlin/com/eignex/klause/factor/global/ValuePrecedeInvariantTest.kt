package com.eignex.klause.factor.global

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValuePrecedeInvariantTest {

    // s=1, t=2, xs = vars 0..2 over [0,3]
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 3) },
        factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2))),
    )

    @Test
    fun `not violated when no t precedes the first s`() {
        val cases = listOf(
            listOf(0L, 1L, 2L), // first s=1 at index 1, first t=2 at index 2
            listOf(0L, 0L, 0L), // neither s nor t occurs
        )
        for (xs in cases) {
            val state = LocalSearchState(problem(), Random(0))
            for (i in 0..2) state.assignment.setInt(i, xs[i])
            state.recompute()
            assertFalse(state.factors[0].isViolated(state, 0), "xs=$xs must satisfy value_precede")
            assertEquals(0, state.factors[0].violationDegree(state, 0), "xs=$xs")
        }
    }

    @Test
    fun `violated when t appears before first s`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[2,1,0]: t=2 at index 0, s=1 at index 1 → violated
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(1, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `delta predicts degree change when eliminating leading t`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[2,2,1]: two bad t's before s → degree=2
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        // Change xs[0]=2 → xs[0]=1, putting s at the front with no bad t's before it
        val delta = state.factors[0].deltaIfIntSet(state, 0, 0, 1)
        state.apply(Move.IntSet(0, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
