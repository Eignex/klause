package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The local-search side of bidirectional cross-engine solution flow (#644): an LS arm offers each
 * improving incumbent to the [LocalSearchParams.pooledIncumbents] exchange — which installs only what
 * it verifies and finds strictly better — and, on restart, adopts a better assignment the exchange
 * holds.
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

    // The four dearest weights are at indices 8, 10, 11, 3; pinning them true forces a worst-case
    // feasible selection, which the pooled optimum (those four false) cannot satisfy.
    private val dearest = listOf(8, 10, 11, 3)
    private val pinnedOptimum = 42.0 // 12 + 11 + 10 + 9

    private fun optimalSample(): Sample {
        // The four cheapest weights are at indices 4, 6, 1, 9.
        val cheapest = weights.indices.sortedBy { weights[it] }.take(4).toSet()
        return Sample(BooleanArray(12) { it in cheapest }, LongArray(0))
    }

    private fun objective() = LinearObjective(boolWeights = weights)

    private fun exchangeHolding(sample: Sample, objective: LinearObjective) =
        IncumbentExchange.minimizing<Sample>().apply { offer(sample, objective.evaluate(sample)) }

    @Test
    fun `each improving incumbent is offered to the shared exchange`() {
        val objective = objective()
        val exchange = IncumbentExchange.minimizing<Sample>()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(maxFlips = 300L, randomSeed = 1L, pooledIncumbents = exchange),
        )
        val sample = assertNotNull(result.assignment)
        val standing = assertNotNull(exchange.current(), "at least the first incumbent must be installed")
        assertEquals(objective.evaluate(sample), standing.objective, "the exchange holds the arm's best")
    }

    @Test
    fun `a pooled optimum is adopted on restart and reported as the result`() {
        val objective = objective()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 300L,
                randomSeed = 1L,
                pooledIncumbents = exchangeHolding(optimalSample(), objective),
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertEquals(optimum, objective.evaluate(sample), "the arm adopts (or matches) the pooled optimum")
    }

    @Test
    fun `offers that do not improve the standing incumbent are not installed`() {
        val objective = objective()
        val exchange = exchangeHolding(optimalSample(), objective)
        LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(maxFlips = 300L, randomSeed = 1L, pooledIncumbents = exchange),
        )
        val standing = assertNotNull(exchange.current())
        assertEquals(1L, standing.version, "nothing beats the standing optimum, so no arm offer is installed")
    }

    @Test
    fun `a pooled assignment that violates the run's pins is not adopted`() {
        val objective = objective()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 300L,
                randomSeed = 1L,
                assumptions = Assumptions(bools = dearest.associateWith { true }),
                pooledIncumbents = exchangeHolding(optimalSample(), objective),
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertTrue(dearest.all { sample.bools[it] }, "the pins hold in the reported assignment")
        assertTrue(
            objective.evaluate(sample) >= pinnedOptimum,
            "a pin-violating foreign assignment is never imported under assumptions",
        )
    }
}
