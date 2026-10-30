package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceRateRewardTest {
    @Test
    fun `each invocation should price its own observations when a configured policy is reused`() {
        val configured = SourceRateReward()
        configured.reward(PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 1, 0, true, false,
            mapOf("Improvement" to 1_000_000.0), 0.0))

        repeat(2) {
            val worker = trackingWorker("finder", 0, ScriptedSearch({ true }) {})
            val result = Portfolio(listOf(worker), fixedBandit(1, 0), baseSliceWork = 1, maxSliceWork = 1,
                reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(8),
                rewardPolicy = configured).use { it.minimize(Cancellation.Never) }

            assertEquals(1.0, result.stats.portfolio.arms.single().meanReward, 1e-12)
        }
    }

    @Test
    fun `silent complete workers should retain coverage under gain rate rewards`() {
        var slices = 0
        val base = ScriptedSearch({ false }) {}
        val proof = object : ResumableSearch by base {
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                base.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (++slices == 3) MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf()), 999.0)
                else null
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) {}).also { it.restartingSearch = true },
            trackingWorker("proof", 1, proof),
        )

        val result = Portfolio(workers, fixedBandit(2, 0), baseSliceWork = 2, maxSliceWork = 2,
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(32),
            rewardPolicy = SourceRateReward()).use { it.minimize(Cancellation.Never) }

        assertIs<MinimizeResult.Optimal>(result)
    }

    @Test
    fun `feasibility should retain ordinary rewards while paying its setup cost`() {
        val reward = SourceRateReward()
        val progress = PortfolioProgress(0, PortfolioPhase.FEASIBILITY, 1, 10, 0, false, false,
            mapOf("Violation" to 0.5), 0.75, constructionNanos = 20)
        val improving = progress.copy(phase = PortfolioPhase.IMPROVEMENT, nanos = 10,
            acceptedIncumbent = true, credit = mapOf("Improvement" to 10.0), constructionNanos = 0)

        assertEquals(0.75, reward.reward(progress))
        reward.reward(improving.copy(arm = 1))

        assertEquals(0.2, reward.reward(improving), 1e-12)
    }

    @Test
    fun `gain rates should include factory cost and count nested initialization only once`() {
        val progress = PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
            mapOf("Improvement" to 10.0), 0.0)
        for (costly in listOf(progress.copy(arm = 0, constructionNanos = 30),
            progress.copy(arm = 0, nanos = 40, initializationNanos = 30, checkingNanos = 5))) {
            val reward = SourceRateReward()
            reward.reward(progress)

            assertEquals(0.2, reward.reward(costly), 1e-12)
        }
    }

    @Test
    fun `unproductive work should be paid by the next gain without charging it twice`() {
        val reward = SourceRateReward()
        val progress = PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
            mapOf("Improvement" to 10.0), 0.0)
        reward.reward(progress)
        val improving = progress.copy(arm = 0)

        assertEquals(0.0, reward.reward(improving.copy(nanos = 30, acceptedIncumbent = false)))
        assertEquals(0.2, reward.reward(improving), 1e-12)
        assertEquals(0.5, reward.reward(improving), 1e-12)
    }

    @Test
    fun `reward should price gain magnitude independently of objective units`() {
        for (scale in listOf(1e-200, 1.0, 1e200, 1e308)) {
            val reward = SourceRateReward()
            val progress = PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
                mapOf("Improvement" to scale), 0.0)
            reward.reward(progress)

            assertEquals(1.0 / 11, reward.reward(progress.copy(arm = 0,
                credit = mapOf("Improvement" to scale / 10))), 1e-12)
        }
    }

    @Test
    fun `larger gains should preserve the relative rate of earlier peer progress`() {
        val reward = SourceRateReward()
        val progress = PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
            mapOf("Improvement" to 1e307), 0.0)
        reward.reward(progress)

        assertEquals(10.0 / 11, reward.reward(progress.copy(arm = 0,
            credit = mapOf("Improvement" to 1e308))), 1e-12)
    }

    @Test
    fun `unaccepted failed refuted idle or unpriced gains should earn no improvement reward`() {
        val progress = PortfolioProgress(0, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
            mapOf("Improvement" to 10.0), 1.0)
        for (unearned in listOf(progress.copy(acceptedIncumbent = false), progress.copy(failed = true),
            progress.copy(refuted = true), progress.copy(idleCredit = true)) +
            listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).map {
                progress.copy(credit = mapOf("Improvement" to it))
            }) {
            assertEquals(0.0, SourceRateReward().reward(unearned))
        }
    }

    @Test
    fun `accepted proof closure should receive terminal reward`() {
        val progress = PortfolioProgress(0, PortfolioPhase.PROOF_CLOSURE, 0, 0, 1, false, false,
            emptyMap(), 0.0)

        assertEquals(1.0, SourceRateReward().reward(progress))
        assertEquals(0.0, SourceRateReward().reward(progress.copy(refuted = true)))
    }
}
