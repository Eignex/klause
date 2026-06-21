package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardinalitySumFactorTest {

    @Test
    fun `holds returns true within min-max window`() {
        val c = Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 1, max = 2)
        val problem = Problem(3, 0, emptyArray(), listOf(c))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `violation degree is distance below min`() {
        val c = Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 3)
        val problem = Problem(3, 0, emptyArray(), listOf(c))
        val state = LocalSearchState(problem, Random(0))
        state.recompute()
        assertEquals(2, state.factors[0].violationDegree(state, 0), "count=0, min=2 → distance 2")
    }

    @Test
    fun `violation degree is distance above max`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        val c = Cardinality(lits, min = 0, max = 1)
        val problem = Problem(3, 0, emptyArray(), listOf(c))
        val state = LocalSearchState(problem, Random(0))
        for (v in 0..2) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(2, state.factors[0].violationDegree(state, 0), "count=3, max=1 → distance 2")
    }

    @Test
    fun `satisfied degree is zero`() {
        val c = Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(c))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(0, true)
        state.recompute()
        assertEquals(0, state.factors[0].violationDegree(state, 0))
        assertTrue(!state.factors[0].isViolated(state, 0))
    }
}
