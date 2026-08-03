package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Solution phasing seeded from a peer arm's incumbent (#644 collaboration): the engine polls
 * [BacktrackParams.pooledSolutionSupplier] at each restart and adopts the pooled assignment as
 * phase hints. The hint only reorders value trials, so it must never compromise the proven optimum.
 */
class PooledSolutionPhasingTest {

    // Choose ≥ 3 of 8 booleans to minimise their weights: the optimum picks the three cheapest.
    private fun problem() = Problem(
        numBoolVars = 8,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Cardinality(IntArray(8) { Lit.make(it, true) }, min = 3, max = 8)),
    )

    private val weights = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L)
    private val optimum = 6.0 // 1 (var4) + 2 (var6) + 3 (var1)

    // A feasible but deliberately suboptimal peer solution: the first three booleans (cost 15).
    private val pooledHint = Sample(BooleanArray(8) { it < 3 }, LongArray(0))

    @Test
    fun `the pooled-solution supplier is polled once per restart`() {
        var polls = 0
        var restarts = 0
        val params = BacktrackParams(
            solutionPhasing = true,
            lubyRestartBase = 1L,
            randomSeed = 1L,
            pooledSolutionSupplier = {
                polls++
                pooledHint
            },
            onEvent = { if (it is SearchEvent.Restart) restarts++ },
        )
        BacktrackSolver(problem().bake()).minimize(LinearObjective(boolWeights = weights), params)
        assertTrue(restarts > 0, "the search must restart for the poll to fire")
        assertEquals(restarts, polls, "the pool is consulted exactly once per restart")
    }

    @Test
    fun `a suboptimal pooled hint does not compromise the proven optimum`() {
        val objective = LinearObjective(boolWeights = weights)
        val params = BacktrackParams(
            solutionPhasing = true,
            lubyRestartBase = 1L,
            randomSeed = 1L,
            pooledSolutionSupplier = { pooledHint },
        )
        val result = BacktrackSolver(problem().bake()).minimize(objective, params)
        val sample = assertNotNull(result.assignment)
        assertEquals(optimum, objective.evaluate(sample), "phase hints reorder trials, they never cut solutions")
    }
}
