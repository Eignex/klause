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
