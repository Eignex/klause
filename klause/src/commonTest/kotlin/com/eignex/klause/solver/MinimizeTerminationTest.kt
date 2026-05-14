package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver

import kotlin.test.Test
import kotlin.test.assertNotNull

class MinimizeTerminationTest {

    /**
     * Regression: a degenerate (all-zero) objective on a constraint-free problem must
     * still terminate within `maxFlips`. Previously the cost==0 / no-progress restart
     * path didn't count against `maxFlips`, so the loop would spin forever.
     */
    @Test
    fun `minimize should terminate on a degenerate objective and constraint-free problem`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
        )
        val solver = LocalSearchSolver(problem)
        // All-zero weights → every assignment evaluates to 0; greedy descent never improves.
        val degenerate = LinearObjective(boolWeights = DoubleArray(4))
        val sample = solver.minimize(
            degenerate,
            LocalSearchParams(maxFlips = 1_000L, randomSeed = 1L),
        )
        // Any feasible assignment is acceptable; we just verify it returned at all.
        assertNotNull(sample)
    }
}
