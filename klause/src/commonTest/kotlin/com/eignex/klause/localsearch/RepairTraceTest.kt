package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepairTraceTest {
    @Test
    fun `capture bounds should decline a whole oversized attempt`() {
        val trace = RepairTrace("strict source order", 1, 2)
        val tooLarge = Sample(booleanArrayOf(), longArrayOf(0, 1, 2))

        assertNull(trace.begin("control", 7, tooLarge, tooLarge))
        assertTrue(trace.attempts.isEmpty())
        assertEquals(0L, trace.coordinateReads)
        assertEquals(1L, trace.declined)
    }

    @Test
    fun `scope capture should own its requested coordinates`() {
        val trace = RepairTrace("strict source order")
        val sample = Sample(booleanArrayOf(), longArrayOf(0, 1))
        val requested = intArrayOf(0)
        val attempt = assertNotNull(trace.begin("control", 7, sample, sample, requestedInts = requested))

        requested[0] = 1
        trace.finish(attempt, RepairEndpoint.Censored)

        assertEquals(0, attempt.requestedInts.single())
        assertEquals(RepairEndpoint.Censored, attempt.endpoint)
    }

    @Test
    fun `a full capture should decline further snapshots`() {
        val trace = RepairTrace("strict source order", 1, 2)
        val sample = Sample(booleanArrayOf(), longArrayOf(0, 1))
        trace.begin("control", 7, sample, sample)

        assertNull(trace.begin("control", 7, sample, sample))

        assertEquals(1, trace.attempts.size)
        assertEquals(2L, trace.coordinateReads)
        assertEquals(1L, trace.declined)
    }

    @Test
    fun `known infeasibility should remain distinct from unfinished repairs`() {
        val trace = RepairTrace("typed repair outcomes")
        val sample = Sample(booleanArrayOf(), longArrayOf(0))
        for ((result, verdict, reason) in listOf(
            Triple(MinimizeResult.Infeasible(), "Infeasible", null),
            Triple(MinimizeResult.Unknown(TerminationReason.Cancelled), "Unknown", TerminationReason.Cancelled),
            Triple(MinimizeResult.Unknown(TerminationReason.BudgetExhausted), "Unknown", TerminationReason.BudgetExhausted),
        )) {
            val attempt = assertNotNull(trace.begin("control", 7, sample, sample))

            trace.recordResult(attempt, result)

            assertEquals(verdict, attempt.repairVerdict)
            assertEquals(reason, attempt.repairReason)
            assertNull(attempt.endpoint)
        }
    }

    @Test
    fun `observed restart phases should preserve witnesses work and random draws`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2))))
        val results = mutableListOf<Triple<Sample?, Double, List<Long>>>()
        val trace = RepairTrace("empty strict move pool; seed 7")
        for (observed in listOf(false, true)) {
            val draws = mutableListOf<Long>()
            val strategy = SourceDrivenStrategy(sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned, perturbation = { draws.add(it.rng.nextLong()); null })
            val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy,
                restartPolicy = IteratedLocalSearchRestart(initialPerturbationStrength = 1),
                seedImplicitOnRestart = true)
            if (observed) engine.repairTrace = trace

            val result = engine.resumable(LinearObjective(), LocalSearchParams(maxFlips = 4, randomSeed = 7,
                initialAssignment = Sample(booleanArrayOf(), longArrayOf(0, 1, 2)))).use {
                assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
            }

            results.add(Triple(result.assignment, result.stats.ls.moves.sum, draws))
        }

        assertEquals(results.first(), results.last())
        val restart = trace.attempts.first { it.owner == "restart" }
        assertEquals(listOf("before", "perturbed", "prepared", "repaired", "endpoint"),
            restart.frames.map { it.stage })
    }

    @Test
    fun `a sliced repair should remain pending and close as censored`() {
        val problem = Problem(0, 32, Array(32) { IntDomain(0, if (it < 2) 1 else 0) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 1)
        val strategy = SourceDrivenStrategy(sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned)
        val trace = RepairTrace("empty strict move pool; seed 7")
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)
        engine.repairTrace = trace
        var polls = 0
        val handle = engine.resumable(LinearObjective(), LocalSearchParams(maxFlips = 1, randomSeed = 7,
            initialAssignment = Sample(booleanArrayOf(), LongArray(32) { if (it == 1) 1L else 0L })))

        assertNull(handle.runSlice(Cancellation { policy.incumbents.isNotEmpty() && ++polls >= 3 },
            Long.MAX_VALUE, -1) {})
        val pending = trace.attempts.last()
        assertNull(pending.endpoint)
        assertEquals("prepared", pending.frames.last().stage)
        handle.close()

        assertEquals(RepairEndpoint.Censored, pending.endpoint)
        assertTrue(pending.frames.none { it.stage == "repaired" })
    }

    @Test
    fun `resuming a pending repair should retain its earlier phases`() {
        val problem = Problem(0, 32, Array(32) { IntDomain(0, if (it < 2) 1 else 0) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1))))
        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 1)
        val strategy = SourceDrivenStrategy(sources = emptyList(), acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned)
        val trace = RepairTrace("empty strict move pool; seed 7")
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy = strategy, restartPolicy = policy)
        engine.repairTrace = trace
        var polls = 0

        engine.resumable(LinearObjective(), LocalSearchParams(maxFlips = 1, randomSeed = 7,
            initialAssignment = Sample(booleanArrayOf(), LongArray(32) { if (it == 1) 1L else 0L }))).use { handle ->
            assertNull(handle.runSlice(Cancellation { policy.incumbents.isNotEmpty() && ++polls >= 3 },
                Long.MAX_VALUE, -1) {})
            val pending = trace.attempts.last()
            val phases = pending.frames.toList()

            assertNotNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

            assertEquals(phases, pending.frames.take(phases.size))
            assertEquals(listOf("before", "perturbed", "prepared", "repaired", "endpoint"),
                pending.frames.map { it.stage })
            assertEquals(RepairEndpoint.Censored, pending.endpoint)
        }
    }
}
