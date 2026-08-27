package com.eignex.klause.factor.table

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementInvariantTest {

    @Test
    fun `satisfied when result equals selected constant element`() {
        // arr=[10, 20, 30]; idx=1 selects arr[1]=20; result=20 → satisfied.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 20)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when result does not match selected constant element`() {
        // arr=[10, 20, 30]; idx=1 selects arr[1]=20; result=10 ≠ 20 → violated.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `violated when index is out of range`() {
        // arr has 3 elements (indices 0..2); idx=-1 is below indexOffset=0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(-1, 5), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, -1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta predicts improvement when result is set to matching value`() {
        // Violated (result=10 ≠ arr[1]=20); setting result=20 should yield delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 1, newValue = 20)
        assertTrue(delta < 0, "setting result=20 should reduce violation; delta=$delta")
    }

    @Test
    fun `satisfied for variable array when result matches selected var`() {
        // result(1) = [v2, v3][idx(0)]; idx=0 selects v2; v2=7, result=7 → satisfied.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 10) },
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0) // idx=0 → selects var 2
        state.assignment.setInt(1, 7) // result=7
        state.assignment.setInt(2, 7) // v2=7
        state.assignment.setInt(3, 3)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }
}
