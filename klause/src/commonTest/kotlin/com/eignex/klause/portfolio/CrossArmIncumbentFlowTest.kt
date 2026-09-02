package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cross-arm half of incumbent flow for the two local-search-class arms: both worker adapters wire
 * [SharedPools.solutions] into their engine, so each arm's improvements are offered to the one verified
 * exchange and an arm entering the pool later never reports worse than the incumbent standing when it
 * started.
 */
class CrossArmIncumbentFlowTest {

    // Pick ≥ 4 of 12 weighted booleans; the optimum picks the four cheapest.
    private val weights = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L, 12L, 4L, 11L, 10L)
    private val objective = LinearObjective(boolWeights = weights)

    private fun problem(): BakedProblem = Problem(
        numBoolVars = weights.size,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality(IntArray(weights.size) { Lit.make(it, true) }, min = 4, max = weights.size),
        ),
    ).bake()

    /** Materialize [config] against [exchange] and run one counted segment of its optimisation. */
    private fun run(
        config: WorkerConfig,
        exchange: IncumbentExchange<Sample, Double>,
        seed: Long,
        budget: Long,
    ): MinimizeResult = config.materialize(
        problem(),
        index = 0,
        armId = 0,
        seed = seed,
        lsLambda = 1.0,
        objective = objective,
        lsObjective = null,
        definitionalSweep = null,
        onEvent = null,
        pools = SharedPools(clauses = SharedClausePool(), cuts = null, solutions = exchange),
    ).use { worker ->
        worker.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never, maxInstructions = budget).last()
    }

    @Test
    fun `a materialized LS arm offers its incumbents to the shared exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val result = run(LocalSearchWorkerConfig.byLabel("cbls/fixed"), exchange, seed = 1L, budget = 300L)
        val standing = assertNotNull(exchange.current(), "the LS arm must install at least its first incumbent")
        assertEquals(result.objectiveValue, standing.objective, "the exchange holds the LS arm's best")
    }

    @Test
    fun `a materialized ALNS arm offers its incumbents to the shared exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val result = run(AlnsWorkerConfig(), exchange, seed = 1L, budget = 300L)
        val standing = assertNotNull(exchange.current(), "the ALNS arm must install at least its first incumbent")
        assertEquals(result.objectiveValue, standing.objective, "the exchange holds the ALNS arm's best")
    }

    @Test
    fun `an arm entering the pool later never reports worse than the standing incumbent`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val first = assertNotNull(run(LocalSearchWorkerConfig.byLabel("cbls/fixed"), exchange, 1L, 300L).objectiveValue)
        val second = assertNotNull(run(AlnsWorkerConfig(), exchange, 2L, 300L).objectiveValue)
        assertTrue(second <= first, "the ALNS arm starts from the LS arm's published incumbent ($second > $first)")
        val standing = assertNotNull(exchange.current())
        assertEquals(second, standing.objective, "one exchange holds the better of the two engines' bests")
    }
}
