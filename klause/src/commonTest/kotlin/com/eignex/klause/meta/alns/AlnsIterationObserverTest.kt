package com.eignex.klause.meta.alns

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.meta.alns.AlnsTestFixtures.bootstrapAt
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectObjective
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectOptimum
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.RepairSearchStep
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.kumulant.bandit.UnivariateBandit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlnsIterationObserverTest : RuntimeInitializedTest() {
    @Test
    fun `accepted nonbest repairs should report the actual updated reward and callback work`() {
        val problem = selectProblem()
        val best = selectOptimum()
        val worse = Sample(BooleanArray(problem.numBoolVars) { true }, LongArray(0))
        val updates = ArrayList<Double>()
        val bandit = object : UnivariateBandit {
            override val nbrArms = 1
            override val random = Random(7)
            override fun choose() = 0
            override fun update(armIndex: Int, value: Double, weight: Double) {
                updates += value
            }
            override fun reset() = Unit
        }
        val observations = ArrayList<AlnsIterationObservation>()
        val observer = AlnsIterationObserver {
            assertEquals(listOf(1.0), updates)
            observations += it
        }
        val alns = Alns(inner = bootstrapAt(problem, best), destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(RepairOperator { context ->
                assertNull(context.recordRepairResult)
                context.recordInnerWork?.invoke(7L, 3L)
                context.recordRepairOutcome?.invoke("Unknown", TerminationReason.BudgetExhausted)
                worse
            }), destroyBandit = bandit, acceptance = AcceptanceCriterion.RandomWalk,
            maxIterations = 1, minDestroyFraction = 1.0, maxDestroyFraction = 1.0)
        alns.iterationObserver = observer.owner("alns/test", 7L, problem.numBoolVars, 0, 0)

        alns.minimize(selectObjective, LocalSearchParams(initialAssignment = best, randomSeed = 7L))

        val record = observations.single()
        assertEquals("Repaired", record.endpoint)
        assertTrue(record.scopeComplete)
        assertEquals(problem.numBoolVars, record.effective)
        assertEquals(selectObjective.evaluate(best), record.best)
        assertEquals(selectObjective.evaluate(worse), record.repaired)
        assertEquals(true, record.accepted)
        assertEquals(false, record.newBest)
        assertEquals(1.0, record.reward)
        assertEquals(7L, record.cpNodes)
        assertEquals(3L, record.lsMoves)
        assertNotNull(record.repairNanos)
        assertEquals("Unknown", record.verdict)
        assertEquals("BudgetExhausted", record.reason)
    }

    @Test
    fun `empty and unresolved iterations should remain in the rejected denominator`() {
        for (empty in listOf(false, true)) {
            val problem = selectProblem()
            val sample = selectOptimum()
            val observations = ArrayList<AlnsIterationObservation>()
            val observer = AlnsIterationObserver { observations += it }
            val alns = Alns(inner = bootstrapAt(problem, sample),
                destroyOperators = listOf(DestroyOperator { _, _, _, _, _ ->
                    FreedVars(if (empty) intArrayOf() else intArrayOf(0), intArrayOf())
                }), repairOperators = listOf(RepairOperator { null }), maxIterations = 1)
            alns.iterationObserver = observer.owner("alns/test", 7L, problem.numBoolVars, 0, 0)

            alns.minimize(selectObjective, LocalSearchParams(initialAssignment = sample))

            val record = observations.single()
            assertEquals(if (empty) "Empty" else "Unresolved", record.endpoint)
            assertTrue(record.scopeComplete)
            assertEquals(0.0, record.reward)
            assertEquals(false, record.accepted)
            assertNull(record.repaired)
        }
    }

    @Test
    fun `scalar reused repair outcomes should not add statistics snapshots`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        for (observed in listOf(false, true)) {
            var reads = 0
            val retained = object : RepairSearch {
                override val stats: SolveStats
                    get() {
                        reads++
                        return SolveStats.EMPTY
                    }
                override fun begin(assumptions: Assumptions, decisionBudget: Long, cutoff: Double,
                    cancellation: ExecutionControl,
                ) = Unit
                override fun runSlice(cancellation: ExecutionControl, millis: Long, work: Long) =
                    RepairSearchStep(sample, TerminationReason.BudgetExhausted)
            }
            val outcomes = ArrayList<Pair<String, TerminationReason?>>()
            val context = RepairContext(bootstrapAt(problem, sample), LocalSearchParams(), selectObjective,
                Assumptions.None, sample, FreedVars(intArrayOf(0), intArrayOf()), repairSearch = retained,
                recordInnerWork = { _, _ -> }, recordRepairOutcome = if (observed) { verdict, reason ->
                    outcomes += verdict to reason
                } else null)

            val repaired = BacktrackRepair().repair(context)

            assertEquals(sample, repaired)
            assertEquals(2, reads)
            assertEquals(if (observed) listOf("BestFound" to TerminationReason.BudgetExhausted) else emptyList(),
                outcomes)
        }
    }

    @Test
    fun `funded observation should preserve seeded choices repairs and random draws`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        val logs = ArrayList<List<Alns.IterationRecord>>()
        val draws = ArrayList<Long>()
        for (observed in listOf(false, true)) {
            val rng = Random(7)
            val alns = Alns(inner = bootstrapAt(problem, sample), repairOperators = listOf(RepairOperator { sample }),
                acceptance = AcceptanceCriterion.RandomWalk, maxIterations = 6, rng = rng)
            if (observed) {
                alns.iterationObserver = AlnsIterationObserver {}.owner("alns/test", 7L, problem.numBoolVars, 0, 0)
            }

            alns.minimize(selectObjective, LocalSearchParams(initialAssignment = sample, randomSeed = 7L))

            logs += alns.iterationLog.toList()
            draws += rng.nextLong()
        }
        assertEquals(logs[0], logs[1])
        assertEquals(draws[0], draws[1])
    }

    @Test
    fun `owners and runs should share one funded bounded observation window`() {
        val observations = ArrayList<AlnsIterationObservation>()
        val observer = AlnsIterationObserver { observations += it }
        var charged = 0L
        val stop = ExecutionControl({ false }, WorkMeter { charged += it })
        repeat(2) { ownerIndex ->
            val owner = observer.owner("alns/test", ownerIndex.toLong(), 1, 0, 0)
            repeat(2) {
                val run = owner.nextRun()
                repeat(10) { iteration ->
                    owner.begin(run, iteration, 2, 0, 0.5, 10.0, 8.0, 1000L, stop)?.finish("Censored")
                }
            }
        }

        assertEquals(32, observations.size)
        assertEquals(8L, observer.omitted)
        assertEquals(4096L, charged)
        assertEquals(charged, observer.work)
        assertEquals(65_536L, observer.modeledBytes)
        assertEquals(32, observations.map { Triple(it.owner, it.run, it.iteration) }.toSet().size)
        assertTrue(observations.all { it.endpoint == "Censored" && it.reward == null })
        assertFalse(observations.any { it.scopeComplete })
    }
}
