package com.eignex.klause.solver.integration

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MinimizeTerminationTest {

    /**
     * A degenerate (all-zero) objective on a constraint-free problem must still terminate within
     * `maxFlips`: the cost==0 / no-progress restart path must count against the flip budget.
     */
    @Test
    fun `minimize should terminate on a degenerate objective and constraint-free problem`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val solver = LocalSearchSolver(problem.bake())
        // All-zero weights: every assignment evaluates to 0; greedy descent never improves.
        val degenerate = LinearObjective(boolWeights = LongArray(4))
        val sample = solver.minimize(
            degenerate,
            LocalSearchParams(maxFlips = 1_000L, randomSeed = 1L),
        ).assignment
        assertNotNull(sample)
    }

    /**
     * Cancellation fired *during* an objective-descent step must be honored promptly. A single greedy
     * descent pass is O(numVars); on a large constraint-free problem the inner per-var poll plus the
     * per-step outer check must stop the run long before a whole scan. With `maxFlips = MAX_VALUE` only
     * cancellation can end the run.
     */
    @Test
    fun `minimize honors cancellation fired mid-descent on a large objective`() {
        val n = 4000
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val solver = LocalSearchSolver(problem.bake(), greedyRepairOnRestart = false)
        var polls = 0
        // Trip only after the descent has polled a while, so cancellation is observed inside the per-var loop.
        val cancellation = Cancellation { ++polls > 100 }
        val result = solver.minimize(
            LinearObjective(boolWeights = LongArray(n) { 1L }),
            LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 7L, cancellation = cancellation),
        )
        assertEquals(
            TerminationReason.Cancelled,
            (result as MinimizeResult.BestFound).reason,
            "minimize should report Cancelled",
        )
        assertTrue(polls in 1 until n, "descent should bail mid-scan: expected 1..<$n polls, got $polls")
    }
}
