package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearSumFactorTest {

    @Test
    fun `vars and coeffs are set from constructor`() {
        val linear = Linear(intArrayOf(3, -2, 5), intArrayOf(0, 1, 2), LinearOp.LE, 10)
        assertTrue(linear.vars.contentEquals(intArrayOf(0, 1, 2)))
        assertTrue(linear.coeffs.contentEquals(intArrayOf(3, -2, 5)))
    }

    @Test
    fun `duplicate variable coefficients coalesce and sum correctly in LS`() {
        val linear = Linear(intArrayOf(2, 3, 4), intArrayOf(0, 1, 0), LinearOp.EQ, 9)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 10), IntDomain(0, 10)), listOf<Factor>(linear))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0), "6·1 + 3·1 = 9 should satisfy EQ 9")
    }

    @Test
    fun `violation degree is distance to bound for LE`() {
        val linear = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 20)), listOf<Factor>(linear))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 8)
        state.recompute()
        assertEquals(3, state.factors[0].violationDegree(state, 0), "8 > 5 by 3")
    }

    @Test
    fun `violation degree is zero when constraint satisfied`() {
        val linear = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.GE, 10)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 10), IntDomain(0, 10)), listOf<Factor>(linear))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 0)
        state.recompute()
        assertEquals(0, state.factors[0].violationDegree(state, 0), "2·5 + 3·0 = 10 >= 10 is satisfied")
    }
}
