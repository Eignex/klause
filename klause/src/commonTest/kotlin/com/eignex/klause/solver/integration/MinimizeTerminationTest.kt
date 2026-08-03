package com.eignex.klause.solver.integration

import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
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
     * per-step outer check must stop after probing far fewer than `numVars` candidates. With
     * `maxFlips = MAX_VALUE` only cancellation can end the run.
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
        val solver = LocalSearchSolver(problem.bake())
        var deltaCalls = 0
        // Incremental linear objective that counts per-move probes, so we observe how far the descent
        // scanned before bailing.
        val objective = object : IncrementalObjective {
            override fun evaluate(sample: Sample): Double {
                var total = 0.0
                for (b in sample.bools) if (b) total += 1.0
                return total
            }

            override fun deltaIfApplied(assignment: Assignment, move: Move): Double {
                deltaCalls++
                return when (move) {
                    is Move.BoolFlip -> if (assignment.boolValue(move.varId)) -1.0 else 1.0
                    is Move.IntSet -> 0.0
                    is Move.Compound -> move.parts.sumOf { deltaIfApplied(assignment, it) }
                }
            }
        }
        // Trip only once the descent has probed some moves, so cancellation is observed *inside* the
        // per-var descent loop via its in-loop poll, independent of poll call-ordering.
        val cancellation = Cancellation { deltaCalls >= 100 }
        val result = solver.minimize(
            LinearObjective(boolWeights = LongArray(n) { 1L }),
            LocalSearchParams(
                maxFlips = Long.MAX_VALUE,
                randomSeed = 7L,
                cancellation = cancellation,
                lsObjective = objective,
            ),
        )
        assertEquals(
            TerminationReason.Cancelled,
            (result as MinimizeResult.BestFound).reason,
            "minimize should report Cancelled",
        )
        assertTrue(
            deltaCalls in 1 until n,
            "descent should bail mid-scan: expected 1..<$n probes, got $deltaCalls",
        )
    }
}
