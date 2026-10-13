package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.PortfolioTerminalCheck
import com.eignex.klause.portfolio.PortfolioTerminalPublication
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.util.Cancellation

internal class LinearPortfolioSearch(
    private val descent: RationalTheoryDescent,
    val invocation: EngineInvocation,
) : ResumableSearch by descent {
    init {
        if (!descent.matchesInvocation(invocation)) {
            val failure = IllegalArgumentException("linear handle differs from its runtime invocation")
            runCatching { descent.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    val result: LinearOptimizationResult? get() = descent.linearResult
}

internal class LinearPortfolioTerminal(
    private val invocation: EngineInvocation,
    private val original: SourceProofContext,
    private val cancellation: Cancellation,
    private val accepted: (LinearOptimizationResult) -> Unit,
) : PortfolioTerminalCheck {
    override fun check(worker: PortfolioWorker, search: ResumableSearch?): PortfolioTerminalPublication? {
        val completed = search as? LinearPortfolioSearch ?: return null
        val result = completed.result ?: return null
        if (result.terminal == null) return null
        if (!worker.permitsSourceProofs) {
            return PortfolioTerminalPublication(Verification.Indeterminate("linear arm is witness-only")) {}
        }
        val workerInvocation = worker.evidenceInvocation ?: return PortfolioTerminalPublication(
            Verification.Indeterminate("linear worker lacks runtime binding")) {}
        val checked = if (!workerInvocation.matches(completed.invocation)) {
            Verification.Rejected("linear handle differs from its worker invocation")
        } else {
            result.verifyRuntimeTerminal(invocation, completed.invocation, original, cancellation)
        }
        return PortfolioTerminalPublication(checked) { accepted(result) }
    }
}
