package com.eignex.klause.meta.alns

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.meta.alns.AlnsTestFixtures.bootstrapAt
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectObjective
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectOptimum
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectProblem
import com.eignex.klause.portfolio.AlnsWorkerConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import com.eignex.kumulant.bandit.univariate.RouletteWheelBandit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AlnsRepairFeedbackTest : RuntimeInitializedTest() {
    @Test
    fun `completed repairs should update their weight regardless of callback work`() {
        val problem = selectProblem()
        val seed = Sample(BooleanArray(problem.numBoolVars) { true }, LongArray(0))
        for (work in listOf(3L, 17L)) {
            val rng = Random(7L)
            val repairBandit = AlnsWorkerConfig.repairBandit(rng, true)
            val alns = Alns(inner = bootstrapAt(problem, seed),
                destroyOperators = listOf(DestroyOperator.Random),
                repairOperators = List(3) {
                    RepairOperator { context ->
                        context.recordInnerWork?.invoke(work, 0L)
                        selectOptimum()
                    }
                }, maxIterations = 2, minDestroyFraction = 1.0, maxDestroyFraction = 1.0, rng = rng,
                repairBandit = repairBandit)
            var completed = 0
            alns.iterationObserver = AlnsIterationObserver { record ->
                completed++
                if (completed == 1) {
                    assertEquals(3.0, record.reward)
                    assertEquals(work, record.cpNodes)
                    assertEquals(1.2, repairBandit.evaluate(record.repair), 1e-12)
                    for (arm in 0 until 3) {
                        if (arm != record.repair) assertEquals(1.0, repairBandit.evaluate(arm))
                    }
                    assertEquals(1.0, assertIs<RouletteWheelBandit>(alns.destroyBandit).evaluate(0))
                }
            }.owner("alns/test", 7L, problem.numBoolVars, 0, 0)

            alns.minimize(selectObjective, LocalSearchParams(initialAssignment = seed, randomSeed = 7L))

            assertEquals(2, completed)
        }
    }

    @Test
    fun `pausing after a repair should retain its feedback without applying it twice`() {
        val problem = selectProblem()
        val seed = selectOptimum()
        var completed = 0
        val rng = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        val repairBandit = AlnsWorkerConfig.repairBandit(rng, true)
        val alns = Alns(inner = bootstrapAt(problem, seed),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = List(3) { RepairOperator { null } }, maxIterations = 2,
            minDestroyFraction = 1.0, maxDestroyFraction = 1.0, rng = rng,
            repairBandit = repairBandit)
        alns.iterationObserver = AlnsIterationObserver { completed++ }
            .owner("alns/test", 7L, problem.numBoolVars, 0, 0)
        alns.resumable(selectObjective, LocalSearchParams(initialAssignment = seed, randomSeed = 7L)).use { handle ->
            assertNull(handle.runSlice(ExecutionControl { true }, Long.MAX_VALUE) {})
            assertEquals(0, completed)
            assertEquals(1.0, repairBandit.evaluate(0))

            assertNull(handle.runSlice(ExecutionControl { completed == 1 }, Long.MAX_VALUE) {})
            assertEquals(1, completed)
            assertEquals(0.9, repairBandit.evaluate(0), 1e-12)

            assertNull(handle.runSlice(ExecutionControl { true }, Long.MAX_VALUE) {})
            assertEquals(0.9, repairBandit.evaluate(0), 1e-12)

            assertNotNull(handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {})
        }

        assertEquals(2, completed)
        assertEquals(0.81, repairBandit.evaluate(0), 1e-12)
        assertEquals(1.0, assertIs<RouletteWheelBandit>(alns.destroyBandit).evaluate(0))
    }

    @Test
    fun `unbound portfolio repairs should keep their ten outcome segment`() {
        val problem = selectProblem()
        val seed = selectOptimum()
        val rng = Random(7L)
        val repairBandit = AlnsWorkerConfig.repairBandit(rng, false)
        var repairs = 0
        val alns = Alns(inner = bootstrapAt(problem, seed),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = List(3) {
                RepairOperator {
                    repairs++
                    null
                }
            }, maxIterations = 9,
            minDestroyFraction = 1.0, maxDestroyFraction = 1.0, rng = rng,
            repairBandit = repairBandit)

        alns.minimize(selectObjective, LocalSearchParams(initialAssignment = seed, randomSeed = 7L))

        for (arm in 0 until 3) assertEquals(1.0, repairBandit.evaluate(arm))
        assertEquals(9, repairs)
    }
}
