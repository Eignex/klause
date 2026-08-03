package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The local-search side of bidirectional cross-engine solution flow (#644): an LS arm publishes each
 * improving incumbent to [LocalSearchParams.improvedSolutionSink] and, on restart, adopts a better
 * assignment offered by [LocalSearchParams.pooledSolutionSupplier].
 */
class LocalSearchPoolFlowTest {

    // Choose ≥ 4 of 12 booleans to minimise their weights: the optimum picks the four cheapest.
    private fun problem() = Problem(
        numBoolVars = 12,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Cardinality(IntArray(12) { Lit.make(it, true) }, min = 4, max = 12)),
    )

    private val weights = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L, 12L, 4L, 11L, 10L)
    private val optimum = 10.0 // 1 (var4) + 2 (var6) + 3 (var1) + 4 (var9)

    private fun optimalSample(): Sample {
        // The four cheapest weights are at indices 4, 6, 1, 9.
        val cheapest = weights.indices.sortedBy { weights[it] }.take(4).toSet()
        return Sample(BooleanArray(12) { it in cheapest }, LongArray(0))
    }

    @Test
    fun `each improving incumbent is published to the sink`() {
        val published = mutableListOf<Pair<Sample, Double>>()
        val objective = LinearObjective(boolWeights = weights)
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 3_000L,
                randomSeed = 1L,
                improvedSolutionSink = { sample, obj -> published.add(sample to obj) },
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertTrue(published.isNotEmpty(), "at least the first incumbent must be published")
        assertEquals(objective.evaluate(sample), published.minOf { it.second }, "best published equals the result")
    }

    @Test
    fun `a pooled optimum is consulted on restart and reported as the result`() {
        var polls = 0
        val optimal = optimalSample()
        val objective = LinearObjective(boolWeights = weights)
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 3_000L,
                randomSeed = 1L,
                pooledSolutionSupplier = {
                    polls++
                    optimal
                },
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertTrue(polls > 0, "the pool must be consulted at each restart")
        assertEquals(optimum, objective.evaluate(sample), "the arm adopts (or matches) the pooled optimum")
    }
}
