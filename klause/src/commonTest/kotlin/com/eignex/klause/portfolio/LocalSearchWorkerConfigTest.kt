package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
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

class LocalSearchWorkerConfigTest {

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
    fun `native initialization should leave the first repair to move sources`() {
        val problem = Problem(
            0, 32, Array(32) { IntDomain(0, if (it < 2) 1 else 0) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        ).bake()
        val objective = LinearObjective(intCoefficients = LongArray(32))
        for (native in listOf(false, true)) {
            val costs = mutableListOf<Long>()
            for (seed in 0L until 16L) {
                var firstCost: Long? = null
                val source = object : MoveSource {
                    override val id = MoveSourceId("initial-repair")
                    override val phase = Phase.Any
                    override val pool = Pool.ScoreOnly
                    override fun generate(state: LocalSearchState, sink: MoveSink) {
                        if (firstCost == null) firstCost = state.cost
                        sink.addIntSet(0, state.assignment.intValue(1))
                    }
                }
                val recipe = LocalSearchCatalog.byLabel("cbls/fixed").withSources {
                    listOf(ConfiguredSource(source))
                }
                val ordinary = LocalSearchWorkerConfig(recipe)
                val config = if (native) ordinary.withoutGreedyRepair() else ordinary
                config.materialize(problem, 0, 0, seed, 1.0, objective, null, null, null, null).use { worker ->
                    checkNotNull(worker.newResumableSearch { Double.POSITIVE_INFINITY }).use { run ->
                        run.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {}
                    }
                }
                costs += checkNotNull(firstCost)
            }

            assertTrue(if (native) costs.any { it > 0L } else costs.all { it == 0L }, "native=$native")
        }
    }

    @Test
    fun `a materialized LS arm should offer its incumbents to the shared exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val result = run(LocalSearchWorkerConfig.byLabel("cbls/fixed"), exchange, seed = 1L, budget = 300L)
        val standing = assertNotNull(exchange.current())
        assertEquals(result.objectiveValue, standing.objective)
    }

    @Test
    fun `an arm entering the pool later should never report worse than the standing incumbent`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val first = assertNotNull(run(LocalSearchWorkerConfig.byLabel("cbls/fixed"), exchange, 1L, 300L).objectiveValue)
        val second = assertNotNull(run(AlnsWorkerConfig(), exchange, 2L, 300L).objectiveValue)
        assertTrue(second <= first, "$second > $first")
        val standing = assertNotNull(exchange.current())
        assertEquals(second, standing.objective)
    }
}
