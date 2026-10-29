package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortfolioProgressTest {
    @Test
    fun `experimental rewards should not extend an unproductive auxiliary lifetime`() {
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }),
            trackingWorker("trial", 1, ScriptedSearch({ false }) { }).also { it.improvementOnly = true },
        )
        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(80), rewardPolicy = PortfolioRewardPolicy { 1.0 }).also {
            it.incumbentProbeArms = setOf(1)
        }.use { it.minimize(Cancellation.Never) }

        assertEquals(12L, result.stats.portfolio.arms[1].work)
    }

    @Test
    fun `idle sharing use should retain its phase credit without charging a segment`() {
        val observed = mutableListOf<PortfolioProgress>()
        val pools = SharedPools(null, null)
        val workers = listOf(
            trackingWorker("producer", 0, ScriptedSearch({ false }) { }),
            trackingWorker("consumer", 1, ScriptedSearch({ it == 0 }) {
                pools.contributions.note(Contribution.Clause, 0)
            }),
        ).onEach { it.sharedPools = pools }
        workers[0].sharing = setOf(Contribution.Clause)

        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(3),
            rewardPolicy = PortfolioRewardPolicy { observed += it; it.ordinaryReward }).use {
            it.minimize(Cancellation.Never)
        }

        val idle = observed.single { it.idleCredit }
        assertEquals(PortfolioPhase.IMPROVEMENT, idle.phase)
        assertEquals(0L, idle.work)
        assertEquals(1.0, idle.credit["ClauseUses"])
        assertEquals(1.0, result.stats.portfolio.arms[0].phases.sumOf { it.credit["ClauseUses"] ?: 0.0 })
    }

    @Test
    fun `a failed lifecycle observer should still close retained handles`() {
        val search = TrackingResumableSearch(null)
        val worker = trackingWorker("search", 0, search)

        assertFailsWith<IllegalStateException> {
            Portfolio.thompson(listOf(worker), diagnostics = PortfolioDiagnostics(1), onProgress = {
                if (it is PortfolioEvent.Lifecycle && it.kind == PortfolioLifecycleKind.RETIRED) error("sink failed")
            }).use { it.minimize(Cancellation.Never) }
        }

        assertEquals(1, search.closes)
    }

    @Test
    fun `reward experiments should retain silent complete worker coverage`() {
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }).also { it.family = ArmFamily.LocalSearch },
            trackingWorker("proof", 1, ScriptedSearch({ false }) { }),
        )
        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(80),
            rewardPolicy = PortfolioRewardPolicy { if (it.arm == 0) 1.0 else 0.0 }).use {
            it.minimize(Cancellation.Never)
        }

        val work = result.stats.portfolio.arms.map { it.work }
        assertTrue(work[1] >= work.sum() / 2 - 2)
    }

    @Test
    fun `accepted progress should distinguish feasibility improvement and closure`() {
        val observations = mutableListOf<PortfolioProgress>()
        var slices = 0
        val base = ScriptedSearch({ it < 2 }) { }
        val search = object : ResumableSearch by base {
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                base.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (++slices == 3) MinimizeResult.Optimal(Sample(BooleanArray(0), LongArray(0)), 998.0)
                else null
            }
        }
        val result = Portfolio.thompson(listOf(trackingWorker("search", 0, search)),
            baseSliceWork = 1, sliceGrowth = 1.0, reseedStaleThreshold = 0,
            diagnostics = PortfolioDiagnostics(10),
            rewardPolicy = PortfolioRewardPolicy { observations += it; it.ordinaryReward }).use {
            it.minimize(Cancellation.Never)
        }

        assertEquals(PortfolioPhase.entries, observations.map { it.phase })
        assertEquals(listOf(1L, 1L, 0L), observations.map { it.acceptedIncumbents })
        assertEquals(1.0, observations.last().credit["ProofClosure"])
        assertEquals(2L, result.stats.portfolio.arms.single().phases.sumOf { it.incumbents })
    }

    @Test
    fun `refuted witness checking should cost work without accepted progress or reward`() {
        val events = mutableListOf<PortfolioEvent>()
        val worker = trackingWorker("search", 0, ScriptedSearch({ true }) { })
        val result = Portfolio(listOf(worker), fixedBandit(1, 0),
            witnessCheck = WitnessCheck { _, _ -> "invalid fixture witness" },
            diagnostics = PortfolioDiagnostics(10), onProgress = events::add,
            rewardPolicy = PortfolioRewardPolicy { error("refuted claims must not earn rewards") }).use {
            it.minimize(Cancellation.Never)
        }

        val progress = events.filterIsInstance<PortfolioEvent.Progressed>().single().progress
        assertEquals(1L, progress.checks)
        assertEquals(0L, progress.acceptedIncumbents)
        assertTrue(progress.refuted)
        assertEquals(0.0, result.stats.portfolio.arms.single().meanReward)
    }

    @Test
    fun `a deferred worker should first query after incumbent availability`() {
        val events = mutableListOf<PortfolioEvent>()
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }),
            trackingWorker("deferred", 1, ScriptedSearch({ false }) { }).also { it.startAfterIncumbent = true },
        )
        Portfolio.thompson(workers, baseSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(10), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        val life = events.filterIsInstance<PortfolioEvent.Lifecycle>().filter { it.arm == 1 }
        val available = life.indexOfFirst { it.kind == PortfolioLifecycleKind.INCUMBENT_AVAILABLE }
        val queried = life.indexOfFirst { it.kind == PortfolioLifecycleKind.FIRST_QUERY }
        assertTrue(available >= 0 && queried > available)
        assertTrue(life.last().kind == PortfolioLifecycleKind.RETIRED)
    }

    @Test
    fun `reward experiments should reject nonfinite scores`() {
        val worker = trackingWorker("search", 0, ScriptedSearch({ false }) { })

        assertFailsWith<IllegalArgumentException> {
            Portfolio.thompson(listOf(worker), diagnostics = PortfolioDiagnostics(1),
                rewardPolicy = PortfolioRewardPolicy { Double.NaN }).use { it.minimize(Cancellation.Never) }
        }
    }
}
