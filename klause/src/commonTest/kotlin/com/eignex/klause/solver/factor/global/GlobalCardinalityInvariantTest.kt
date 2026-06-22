package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.GlobalCardinality
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalCardinalityInvariantTest {

    private fun problem(
        xs: IntArray,
        cover: IntArray,
        countLow: IntArray,
        countHigh: IntArray,
        domainHi: Int = 5,
    ): Problem {
        val n = xs.size
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, domainHi) },
            factors = arrayOf<Factor>(GlobalCardinality(xs, cover, countLow = countLow, countHigh = countHigh)),
        )
    }

    @Test
    fun `satisfied when all counts within bounds`() {
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1, 2), intArrayOf(1, 1), intArrayOf(2, 2))
        val state = LocalSearchState(p, Random(0))
        // xs = [1, 2, 1]: count(1)=2, count(2)=1 — both within [1,2]
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when count falls below lower bound`() {
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(3), intArrayOf(2), intArrayOf(3))
        val state = LocalSearchState(p, Random(0))
        // xs = [3, 3, 0]: count(3)=2, needs at least 2 — actually count=2 is exactly 2, no violation
        // Let's use count(3)=1 < lo=2
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(1, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violation degree equals total count deviation`() {
        // cover=[1,2], lo=[2,2], hi=[3,3]. xs=[0,0,0]: count(1)=0 (2 short), count(2)=0 (2 short) → degree 4
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1, 2), intArrayOf(2, 2), intArrayOf(3, 3))
        val state = LocalSearchState(p, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(4, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `delta predicts degree change when reassigning to cover value`() {
        // cover=[1], lo=[2], hi=[2]. xs=[0,0,0]: violated, count(1)=0, need 2.
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1), intArrayOf(2), intArrayOf(2))
        val state = LocalSearchState(p, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 0, 1)
        state.apply(Move.IntSet(0, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
