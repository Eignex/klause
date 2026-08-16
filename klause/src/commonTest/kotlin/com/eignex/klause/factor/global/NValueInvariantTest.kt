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

class NValueInvariantTest {

    // Var 0 = n (target), vars 1..3 = xs
    private fun problem(domainHi: Int = 3): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, domainHi.toLong()) },
        factors = arrayOf<Factor>(NValue(n = 0, xs = intArrayOf(1, 2, 3))),
    )

    @Test
    fun `not violated when distinct count equals n`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // n=3, xs=[0,1,2] → 3 distinct values, matches n=3
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when distinct count differs from n by the size of the mismatch`() {
        // n=3 throughout; degree = |n - distinct(xs)|.
        val cases = listOf(
            listOf(0L, 0L, 1L) to 1,
            listOf(0L, 0L, 0L) to 2,
        )
        for ((xs, degree) in cases) {
            val state = LocalSearchState(problem(), Random(0))
            state.assignment.setInt(0, 3)
            for (i in 0..2) state.assignment.setInt(1 + i, xs[i])
            state.recompute()
            assertTrue(state.factors[0].isViolated(state, 0), "xs=$xs must violate nvalue")
            assertEquals(degree, state.factors[0].violationDegree(state, 0), "xs=$xs")
        }
    }

    @Test
    fun `delta predicts degree change when introducing new distinct value`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // n=3, xs=[0,0,0] → degree=2; change xs[0]=0 to xs[0]=1 → distinct=2, degree=1
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 1, 1)
        state.apply(Move.IntSet(1, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
