package com.eignex.klause.portfolio

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlnsWorkerConfigTest : RuntimeInitializedTest() {
    @Test
    fun `only matching peer clauses should guide materialized ALNS neighborhoods`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 6, 7), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1, 1), intArrayOf(0, 6, 7), LinearOp.GE, 0),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(8) { 1L })
        val clauses = SharedClausePool()
        clauses.publish(listOf(SharedClause(intArrayOf(), longArrayOf(6, 0, 1, 0, 7, 0, 1, 0), 2)), origin = 0)
        val matching = ModelIdentity.of(problem, objective)
        val foreign = Problem(0, 8, Array(8) { IntDomain(0, 1) }, emptyArray()).bake()
        val identities = listOf(matching, null, ModelIdentity.of(problem, null), ModelIdentity.of(foreign, objective))
        for (identity in identities) {
            val pools = SharedPools(clauses, null, identity = identity)
            var changes = 0L
            for (seed in 0L until 6L) {
                val worker = AlnsWorkerConfig(AlnsProfile("test", 0.5, 0.5, 0.0, 0.98))
                    .materialize(problem, index = 0, armId = 1, seed = seed, lsLambda = 1.0,
                        objective = objective, lsObjective = null, definitionalSweep = null,
                        onEvent = null, pools = pools)
                val result = worker.use {
                    it.improvements({ Double.POSITIVE_INFINITY }, ExecutionControl.Never,
                        warmStart = Sample(BooleanArray(0), LongArray(8) { 1L }), maxInstructions = 1).last()
                }
                val sample = assertNotNull(result.assignment)
                assertTrue(sample.intValue(6) + sample.intValue(7) >= 1L)
                changes += worker.sharingMeter?.snapshot()?.clauses?.get("neighborhoodChanges") ?: 0L
            }
            assertEquals(identity === matching, changes > 0L)
        }
    }

    @Test
    fun `bootstrap should consume same model peer clauses and credit their publisher`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 0),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val clauses = SharedClausePool()
        clauses.publish(listOf(SharedClause(emptyIntArray(), longArrayOf(1, 0, 1, 0), 1)),
            origin = 0, isGlobal = true)
        val tally = ContributionTally()
        val pools = SharedPools(clauses, null, contributions = tally, identity = ModelIdentity.of(problem, objective))
        val worker = AlnsWorkerConfig().materialize(problem, index = 0, armId = 1, seed = 0,
            lsLambda = 1.0, objective = objective, lsObjective = null, definitionalSweep = null,
            onEvent = null, pools = pools)

        val result = worker.use {
            it.improvements({ Double.POSITIVE_INFINITY }, ExecutionControl.Never, maxInstructions = 1).last()
        }
        val credits = HashMap<Pair<Contribution, Int>, Double>()
        tally.drain { kind, origin, amount -> credits[kind to origin] = amount }

        assertEquals(1L, assertNotNull(result.assignment).intValue(1))
        assertEquals(mapOf((Contribution.Clause to 0) to 1.0), credits)
    }

    @Test
    fun `bootstrap should withhold peer clauses without the exact pool identity`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 0),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val foreign = Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()).bake()
        for (identity in listOf(null, ModelIdentity.of(problem, null), ModelIdentity.of(foreign, objective))) {
            val clauses = SharedClausePool()
            clauses.publish(listOf(SharedClause(emptyIntArray(), longArrayOf(1, 0, 1, 0), 1)), origin = 0)
            val tally = ContributionTally()
            val pools = SharedPools(clauses, null, contributions = tally, identity = identity)
            val worker = AlnsWorkerConfig().materialize(problem, index = 0, armId = 1, seed = 0,
                lsLambda = 1.0, objective = objective, lsObjective = null, definitionalSweep = null,
                onEvent = null, pools = pools)

            val result = worker.use {
                it.improvements({ Double.POSITIVE_INFINITY }, ExecutionControl.Never, maxInstructions = 1).last()
            }
            val credits = HashMap<Pair<Contribution, Int>, Double>()
            tally.drain { kind, origin, amount -> credits[kind to origin] = amount }

            assertEquals(1L, assertNotNull(result.assignment).intValue(1))
            assertEquals(emptyMap(), credits)
        }
    }

    @Test
    fun `inner search should initialize shared definitions before spending moves`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 9)),
            arrayOf<Factor>(Product(0, 1, 2)),
        ).bake()
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        for (seed in listOf(0L, 1L)) {
            val worker = AlnsWorkerConfig().materialize(
                problem,
                index = 0,
                armId = 0,
                seed = seed,
                lsLambda = 1.0,
                objective = null,
                lsObjective = null,
                definitionalSweep = sweep,
                onEvent = null,
                pools = null,
            )
            try {
                val result = assertIs<SolveResult.Sat>(worker.solve(ExecutionControl.Never, maxInstructions = 1))

                assertEquals(
                    result.assignment.ints[0] * result.assignment.ints[1],
                    result.assignment.ints[2],
                )
                assertEquals(0.0, result.stats.ls.moves.sum)
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun `diverse should cycle the curated regimes and wrap past the pool`() {
        val curated = AlnsProfile.Curated.map { it.label }
        assertEquals(
            curated,
            AlnsWorkerConfig.diverse(curated.size).map { it.profile.label },
        )
        assertEquals(
            curated + curated.take(2),
            AlnsWorkerConfig.diverse(curated.size + 2).map { it.profile.label },
        )
    }
}
