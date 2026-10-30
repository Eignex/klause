package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRateRewardTest {
    @Test
    fun `family gain rates should pay unproductive sibling costs`() {
        for (scale in listOf(1e-200, 1.0, 1e200, 1e308)) {
            val gain = PortfolioProgress(2, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
                mapOf("Improvement" to scale), 0.0)
            val sibling = gain.copy(arm = 1, nanos = 90, acceptedIncumbent = false)
            for (unproductive in listOf(sibling, sibling.copy(failed = true), sibling.copy(refuted = true))) {
                val reward = SourceRateReward(priceFamilies = true).fresh(
                    listOf(ArmFamily.LocalSearch, ArmFamily.LocalSearch, ArmFamily.Backtrack))
                reward.reward(gain.copy(arm = 0, acceptedIncumbent = false))
                reward.reward(unproductive)
                reward.reward(gain)

                assertEquals(5.0 / 6, reward.reward(gain.copy(arm = 0)), 1e-12)
                assertEquals(1.0 / 12, assertNotNull(reward.familyReward), 1e-12)
            }
        }
    }

    @Test
    fun `family feasibility rewards should retain feedback while pricing setup`() {
        val reward = SourceRateReward(priceFamilies = true).fresh(listOf(ArmFamily.LocalSearch, ArmFamily.Backtrack))
        val progress = PortfolioProgress(0, PortfolioPhase.FEASIBILITY, 1, 10, 0, false, false,
            mapOf("Violation" to 0.5), 0.75, constructionNanos = 20)
        val improving = progress.copy(phase = PortfolioPhase.IMPROVEMENT, nanos = 10,
            acceptedIncumbent = true, credit = mapOf("Improvement" to 10.0), constructionNanos = 0)

        assertEquals(0.75, reward.reward(progress))
        assertNull(reward.familyReward)
        reward.reward(improving.copy(arm = 1))

        assertEquals(0.2, reward.reward(improving), 1e-12)
        assertEquals(0.2, assertNotNull(reward.familyReward), 1e-12)
    }

    @Test
    fun `retired failure costs should remain in peer gain rates without earning reward`() {
        for (refuted in listOf(false, true)) {
            val observed = mutableListOf<PortfolioProgress>()
            var stop = false
            val workers = listOf(
                trackingWorker("failed", 0, ScriptedSearch({ refuted }, start = 2_000.0) {
                    if (!refuted) error("fixture failure")
                }).also { it.constructionNanos = 30_000_000 },
                trackingWorker("finder", 1, ScriptedSearch({ true }, perSlice = 10) {})
                    .also { it.restartingSearch = true },
                trackingWorker("peer", 2, ScriptedSearch({ true }, start = 990.0) {})
                    .also { it.restartingSearch = true },
            )

            val result = Portfolio(workers, fixedBandit(3, 1), baseSliceWork = 1, maxSliceWork = 1,
                reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(100),
                witnessCheck = WitnessCheck { _, objective -> if (objective == 1_999.0) "refuted fixture" else null },
                onProgress = { event ->
                    if (event is PortfolioEvent.Progressed && !event.progress.idleCredit) {
                        observed += event.progress
                        if (event.progress.arm == 1 && event.progress.phase == PortfolioPhase.IMPROVEMENT) stop = true
                    }
                }, rewardPolicy = SourceRateReward()).use { it.minimize(Cancellation { stop }) }

            val failed = observed.single { it.arm == 0 }
            val own = observed.filter { it.arm == 1 }
            val peer = observed.single { it.arm == 2 }
            val ownCost = own.sumOf { it.nanos.toDouble() + it.constructionNanos }
            val peerCost = peer.nanos.toDouble() + peer.constructionNanos + failed.nanos + failed.constructionNanos
            val ownRate = requireNotNull(own.last().credit["Improvement"]) / ownCost
            val peerRate = requireNotNull(peer.credit["Improvement"]) / peerCost
            val expected = ownRate / (ownRate + peerRate)
            assertTrue(failed.failed || failed.refuted)
            assertEquals(0.0, result.stats.portfolio.arms[0].meanReward)
            assertEquals(2, own.size)
            assertEquals((own.first().ordinaryReward + expected) / 2,
                result.stats.portfolio.arms[1].meanReward, 1e-12)
        }
    }

    @Test
    fun `each invocation should price its own observations when a configured policy is reused`() {
        for (priceFamilies in listOf(false, true)) {
            val configured = SourceRateReward(priceFamilies)
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
    }

    @Test
    fun `silent complete workers should retain coverage under gain rate rewards`() {
        for (priceFamilies in listOf(false, true)) {
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
                rewardPolicy = SourceRateReward(priceFamilies)).use { it.minimize(Cancellation.Never) }

            assertIs<MinimizeResult.Optimal>(result)
        }
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
        for (priceFamilies in listOf(false, true)) {
            val reward = SourceRateReward(priceFamilies).fresh(listOf(ArmFamily.LocalSearch, ArmFamily.Backtrack))
            val progress = PortfolioProgress(1, PortfolioPhase.IMPROVEMENT, 1, 10, 0, true, false,
                mapOf("Improvement" to 1e307), 0.0)
            reward.reward(progress)

            assertEquals(10.0 / 11, reward.reward(progress.copy(arm = 0,
                credit = mapOf("Improvement" to 1e308))), 1e-12)
            if (priceFamilies) assertEquals(10.0 / 11, assertNotNull(reward.familyReward), 1e-12)
        }
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
            for (priceFamilies in listOf(false, true)) {
                val reward = SourceRateReward(priceFamilies).fresh(listOf(ArmFamily.LocalSearch))
                reward.reward(progress)

                assertEquals(0.0, reward.reward(unearned))
                if (priceFamilies) assertEquals(0.0, reward.familyReward)
            }
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
