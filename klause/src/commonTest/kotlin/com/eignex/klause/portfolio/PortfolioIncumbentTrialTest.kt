package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioIncumbentTrialTest {
    @Test
    fun `a pending trial should receive earlier elapsed service without displacing silent proof coverage`() {
        for (bounded in listOf(false, true)) {
            val clock = TestTimeSource()
            val started = clock.markNow()
            val proofTime = LongArray(2)
            val waitingQuanta = mutableListOf<Long>()
            var trialSlices = 0
            var resumedAt = 0L
            var elapsedAtTrial = 0L
            var proofAtTrial = LongArray(2)
            var restoredQuantum: Long? = null
            val proofs = List(2) { arm ->
                var slices = 0
                val source = ScriptedSearch({ false }) {}
                object : ResumableSearch by source {
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                        val elapsed = if (slices++ == 0) 10L else sliceMillis.coerceAtLeast(1L)
                        if (trialSlices == 1) waitingQuanta += sliceMillis
                        if (trialSlices >= 2) restoredQuantum = sliceMillis
                        proofTime[arm] += elapsed
                        clock += elapsed.milliseconds
                        return source.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                    }
                }
            }
            val trialBase = ScriptedSearch({ false }) {
                trialSlices++
                if (trialSlices == 1) clock += 1_000.milliseconds
                else {
                    if (trialSlices == 2) {
                        resumedAt = started.elapsedNow().inWholeMilliseconds
                        elapsedAtTrial = resumedAt - 10L
                        proofAtTrial = proofTime.copyOf()
                    }
                    clock += 100.milliseconds
                }
            }
            val trial = object : ResumableSearch by trialBase, ReseedGuard {
                override val canReseed: Boolean get() = false
                override val incumbentTrialPending: Boolean get() = trialSlices == 1
            }
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { clock += 10.milliseconds })
                    .also { it.family = ArmFamily.LocalSearch },
                trackingWorker("proof-one", 1, proofs[0]),
                trackingWorker("proof-two", 2, proofs[1]),
                trackingWorker("trial", 3, trial).also {
                    it.family = ArmFamily.LocalSearch
                    it.incumbentTrialUsesProbeQuantum = bounded
                },
            )

            Portfolio(workers, fixedBandit(4, 3), baseSliceMillis = 1_000, maxSliceMillis = 1_000,
                probeSliceMillis = 10, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
                reseedStaleThreshold = 0).also { it.runTimeSource = clock }.use {
                it.minimize(Cancellation { (!bounded && trialSlices >= 2) || restoredQuantum != null ||
                    started.elapsedNow().inWholeMilliseconds > 10_000L })
            }

            assertEquals(2, trialSlices)
            assertTrue(if (bounded) resumedAt <= 2_200L else resumedAt >= 2_800L)
            assertTrue(waitingQuanta.isNotEmpty())
            assertTrue(waitingQuanta.all { if (bounded) it <= 10L else it > 10L })
            if (bounded) assertTrue(requireNotNull(restoredQuantum) > 10L)
            for (elapsed in proofAtTrial) assertTrue(elapsed >= elapsedAtTrial / 4L - 10L)
        }
    }

    @Test
    fun `withdrawing a pending trial should restore ordinary elapsed claims`() {
        val clock = TestTimeSource()
        val started = clock.markNow()
        var pending = false
        var trialSlices = 0
        var proofSlices = 0
        var shortQuantum: Long? = null
        var restoredQuantum: Long? = null
        val proofBase = ScriptedSearch({ false }) {}
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                if (pending) {
                    shortQuantum = sliceMillis
                    pending = false
                } else if (trialSlices > 0) restoredQuantum = sliceMillis
                clock += (if (proofSlices++ == 0) 1L else sliceMillis.coerceAtLeast(1L)).milliseconds
                return proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
            }
        }
        val trialBase = ScriptedSearch({ false }) {
            trialSlices++
            pending = true
            clock += 1_000.milliseconds
        }
        val trial = object : ResumableSearch by trialBase, ReseedGuard {
            override val canReseed: Boolean get() = false
            override val incumbentTrialPending: Boolean get() = pending
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { clock += 1.milliseconds })
                .also { it.family = ArmFamily.LocalSearch },
            trackingWorker("proof", 1, proof),
            trackingWorker("trial", 2, trial).also {
                it.family = ArmFamily.LocalSearch
                it.incumbentTrialUsesProbeQuantum = true
            },
        )

        Portfolio(workers, fixedBandit(3, 2), baseSliceMillis = 1_000, maxSliceMillis = 1_000,
            probeSliceMillis = 10, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            reseedStaleThreshold = 0).also { it.runTimeSource = clock }.use {
            it.minimize(Cancellation { restoredQuantum != null ||
                started.elapsedNow().inWholeMilliseconds > 10_000L })
        }

        assertEquals(1, trialSlices)
        assertTrue(requireNotNull(shortQuantum) <= 10L)
        assertTrue(requireNotNull(restoredQuantum) > 10L)
    }

    @Test
    fun `equal finite cutoffs should preserve proof funding while a pending trial continues`() {
        for (initial in listOf(1L, -1L)) {
            for (cutoffView in listOf(false, true)) for (sameCutoff in listOf(true, false)) {
                val clock = TestTimeSource()
                val started = clock.markNow()
                val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
                val incumbents = PortfolioIncumbents.exact(objective)
                val assignments = IncumbentExchange.minimizing<Sample>()
                val initialValue = BigFraction.ofLong(initial)
                val first = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(initial.toDouble()),
                    exactReals = listOf(initialValue))
                val step = BigFraction.ofLong(if (sameCutoff) 1_000_000_000_000_000_000L else 100L).reciprocal()
                var numericalSlices = 0
                var finiteSlices = 0
                var trialSlices = 0
                var numericalAtTrial = 0
                var finiteAtTrial = 0
                var readBound: () -> Double = { Double.POSITIVE_INFINITY }
                val finiteBounds = mutableListOf<Double>()
                val exactReads = mutableListOf<BigFraction>()
                val accepted = mutableListOf<BigFraction>()
                val finder = TrackingResumableSearch(null,
                    incumbent = MinimizeResult.BestFound(first, initial.toDouble(), TerminationReason.BudgetExhausted),
                    onRun = {
                        clock += 100.milliseconds
                        assignments.offer(first, initial.toDouble())
                    })
                val numericalBase = TrackingResumableSearch(null)
                val numerical = object : ResumableSearch by numericalBase {
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                        numericalSlices++
                        exactReads += requireNotNull(incumbents.exchange.current()).objective
                        clock += 100.milliseconds
                        if (numericalSlices <= 4) {
                            val value = initialValue - BigFraction.ofLong(numericalSlices.toLong()) * step
                            onIncumbent(MinimizeResult.BestFound(first.copy(reals = doubleArrayOf(value.toDouble()),
                                exactReals = listOf(value)), value.toDouble(), TerminationReason.BudgetExhausted))
                        }
                        clock += 1.milliseconds
                        return null
                    }
                }
                val finite = ScriptedSearch({ false }) {
                    finiteSlices++
                    finiteBounds += readBound()
                    assertEquals(1L, requireNotNull(assignments.current()).version)
                    clock += 100.milliseconds
                }
                val trialBase = ScriptedSearch({ false }) {
                    trialSlices++
                    if (trialSlices == 2) {
                        numericalAtTrial = numericalSlices
                        finiteAtTrial = finiteSlices
                    }
                    clock += 10.milliseconds
                }
                val trial = object : ResumableSearch by trialBase, ReseedGuard {
                    override val canReseed: Boolean get() = false
                    override val incumbentTrialPending: Boolean get() = trialSlices == 1
                }
                val workers = listOf(
                    trackingWorker("finder", 0, finder).also { it.family = ArmFamily.LocalSearch },
                    trackingWorker("numerical", 1, numerical),
                    PortfolioWorker.of("finite", 2, TrackingResumableOptimizer(finite).session(), BacktrackParams(),
                        objective, withBound = { params, supplier ->
                            readBound = supplier
                            params
                        }).also { it.canonicalIncumbentIsCutoff = cutoffView },
                    trackingWorker("trial", 3, trial).also { it.family = ArmFamily.LocalSearch },
                )

                Portfolio(workers, fixedBandit(4, 0), reseedStaleThreshold = 0).also {
                    it.runTimeSource = clock
                }.use {
                    it.minimize(Cancellation { (numericalSlices >= 5 && trialSlices >= 2) ||
                        started.elapsedNow().inWholeMilliseconds > 12_000L }, null, incumbents,
                        onAccepted = { point -> accepted += point.objective })
                }

                val retainedCutoff = cutoffView && sameCutoff
                assertEquals(if (retainedCutoff) 1 else 4, finiteAtTrial,
                    "cutoffView=$cutoffView sameCutoff=$sameCutoff")
                assertEquals(if (retainedCutoff) 2 else 5, numericalAtTrial,
                    "cutoffView=$cutoffView sameCutoff=$sameCutoff")
                assertEquals(5, accepted.size)
                assertEquals(accepted, exactReads)
                assertTrue(accepted.zipWithNext().all { (standing, candidate) -> candidate < standing })
                assertEquals(sameCutoff, finiteBounds.distinct().size == 1)
            }
        }
    }

    @Test
    fun `a pending incumbent trial should continue without displacing silent proof coverage`() {
        var proofSlices = 0
        var trialSlices = 0
        val proof = ScriptedSearch({ false }) { proofSlices++ }
        val trialBase = ScriptedSearch({ false }) { trialSlices++ }
        val trial = object : ResumableSearch by trialBase, ReseedGuard {
            override val canReseed: Boolean get() = false
            override val incumbentTrialPending: Boolean get() = trialSlices in 1..2
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                trialBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (trialSlices == 3) MinimizeResult.Unknown(TerminationReason.BudgetExhausted) else null
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) {}).also { it.restartingSearch = true },
            trackingWorker("proof", 1, proof),
            trackingWorker("trial", 2, trial).also { it.family = ArmFamily.LocalSearch },
        )

        Portfolio(workers, fixedBandit(3, 0), baseSliceWork = 2, maxSliceWork = 2,
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(32, 1) {}).use {
            it.minimize(Cancellation.Never)
        }

        assertEquals(3, trialSlices)
        assertTrue(proofSlices >= 3)
    }
}
