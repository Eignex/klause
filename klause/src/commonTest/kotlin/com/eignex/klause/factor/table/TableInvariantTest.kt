package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class TableInvariantTest {

    @Test
    fun `delta is negative when move brings assignment closer to a tuple`() {
        // Violated: (1,1). Closest tuple is (0,1) or (2,3) at distance 1.
        // Setting x0=0 brings us to (0,1) → distance 0 → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 1, 2, 3)),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 0, newValue = 0)
        assertTrue(delta < 0, "move to tuple should reduce violation; delta=$delta")
    }

}
