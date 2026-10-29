package com.eignex.klause.portfolio

import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioIncumbentTrialTest {
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
