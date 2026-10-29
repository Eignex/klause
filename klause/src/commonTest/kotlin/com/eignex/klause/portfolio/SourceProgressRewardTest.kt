package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceProgressRewardTest {
    @Test
    fun `a silent complete worker should retain coverage under source progress rewards`() {
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
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(32, 1) {},
            rewardPolicy = SourceProgressReward).use { it.minimize(Cancellation.Never) }

        assertIs<MinimizeResult.Optimal>(result)
    }

    @Test
    fun `feasibility should retain ordinary progress rewards`() {
        val progress = PortfolioProgress(0, PortfolioPhase.FEASIBILITY, 100, 1_000, 0, false, false,
            mapOf("Violation" to 0.5), 0.75)

        assertEquals(0.75, SourceProgressReward.reward(progress))
    }

    @Test
    fun `improvement rewards should require accepted source progress`() {
        val progress = PortfolioProgress(0, PortfolioPhase.IMPROVEMENT, 100, 1_000, 0, false, false,
            mapOf("Violation" to 0.5, "Glue" to 3.0), 0.75)

        assertEquals(0.0, SourceProgressReward.reward(progress))
        assertEquals(1.0, SourceProgressReward.reward(progress.copy(acceptedIncumbent = true)))
        assertEquals(1.0, SourceProgressReward.reward(progress.copy(phase = PortfolioPhase.PROOF_CLOSURE)))
    }

    @Test
    fun `failed or refuted segments should earn no reward in any phase`() {
        for (phase in PortfolioPhase.entries) {
            val progress = PortfolioProgress(0, phase, 100, 1_000, 1, true, false, emptyMap(), 1.0)

            assertEquals(0.0, SourceProgressReward.reward(progress.copy(failed = true)))
            assertEquals(0.0, SourceProgressReward.reward(progress.copy(refuted = true)))
        }
    }
}
