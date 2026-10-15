package com.eignex.klause.factor.circuit

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubcircuitInvariantTest {

    private fun problem(n: Int): Problem {
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `single included node should yield positive cost`() {
        val problem = problem(4)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(state.intPayload[0] > 0)
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `deltaIfIntSet should predict the cost change`() {
        val n = 4
        val problem = problem(n)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertEquals(0, state.intPayload[0])

        val predicted = state.factors[0].deltaIfIntSet(state, 0, intVar = 2, newValue = 3)
        state.apply(IntSet(2, 3))
        val actual = state.intPayload[0]
        assertEquals(actual, predicted)
        assertTrue(actual > 0)
    }
}
