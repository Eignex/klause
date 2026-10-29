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
import kotlin.test.assertTrue

class ValuePrecedeInvariantTest {

    @Test
    fun `equal precedence values should count every occurrence as a violation`() {
        val state = LocalSearchState(
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = Array(3) { IntDomain(0, 3) },
                factors = emptyArray(),
            ).bake(),
            Random(0),
        )
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        val invariant = ValuePrecedeInvariant(s = 2, t = 2, xs = intArrayOf(0, 1, 2))

        val degree = invariant.violationDegree(state, 0)
        val delta = invariant.deltaIfIntSet(state, 0, intVar = 0, newValue = 0)

        assertTrue(invariant.isViolated(state, 0))
        assertEquals(2, degree)
        assertEquals(-1, delta)
    }

    @Test
    fun `committed cost should use the exact degree after a stateless apply`() {
        val state = LocalSearchState(problem().bake(), Random(0))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1)
        state.recompute()
        val before = state.cost
        val predicted = state.netDelta(Move.IntSet(0, 1))

        state.apply(Move.IntSet(0, 1))

        assertEquals(-before, predicted)
        assertEquals(0L, state.cost)
        assertEquals(state.factors[0].violationDegree(state, 0), state.factorDegree[0])
        state.recompute()
        assertEquals(0L, state.cost)
    }

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 3) },
        factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2))),
    )

    @Test
    fun `value precede should be violated when t appears before the first s`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(1, state.factors[0].violationDegree(state, 0))
    }
}
