package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.PortfolioLifecycleStats
import com.eignex.klause.solver.result.PortfolioPhaseStats

internal class PortfolioProgressLog(workers: List<PortfolioWorker>) {
    private val phases = Array(workers.size) { Array(PortfolioPhase.entries.size) { phase ->
        PortfolioPhaseStats(PortfolioPhase.entries[phase])
    } }
    private val lifecycle = Array(workers.size) { arm ->
        PortfolioLifecycleStats(workers[arm].createdNanos, workers[arm].constructionNanos)
    }

    fun record(progress: PortfolioProgress, checkingNanos: Long) {
        val old = phases[progress.arm][progress.phase.ordinal]
        phases[progress.arm][progress.phase.ordinal] = old.copy(
            segments = old.segments + if (progress.idleCredit) 0 else 1,
            work = old.work + progress.work, nanos = old.nanos + progress.nanos,
            checks = old.checks + progress.checks, checkingNanos = old.checkingNanos + checkingNanos,
            incumbents = old.incumbents + progress.acceptedIncumbents,
            closures = old.closures + if (progress.phase == PortfolioPhase.PROOF_CLOSURE) 1 else 0,
            credit = (old.credit.keys + progress.credit.keys).associateWith {
                (old.credit[it] ?: 0.0) + (progress.credit[it] ?: 0.0)
            },
            initializationNanos = old.initializationNanos + progress.initializationNanos,
            initializationWork = old.initializationWork + progress.initializationWork,
            preparationNanos = old.preparationNanos + if (progress.preparing) progress.nanos else 0,
        )
    }

    fun observe(arm: Int, kind: PortfolioLifecycleKind, nanos: Long) {
        val old = lifecycle[arm]
        lifecycle[arm] = when (kind) {
            PortfolioLifecycleKind.CREATED -> old
            PortfolioLifecycleKind.FIRST_QUERY -> old.copy(firstQueryNanos = old.firstQueryNanos ?: nanos)
            PortfolioLifecycleKind.INCUMBENT_AVAILABLE -> old.copy(
                incumbentAvailableNanos = old.incumbentAvailableNanos ?: nanos)
            PortfolioLifecycleKind.INCUMBENT_READ -> old.copy(
                incumbentReadNanos = old.incumbentReadNanos ?: nanos, incumbentReads = old.incumbentReads + 1)
            PortfolioLifecycleKind.INCUMBENT_IMPORTED -> old.copy(
                incumbentImportedNanos = old.incumbentImportedNanos ?: nanos,
                incumbentImports = old.incumbentImports + 1)
            PortfolioLifecycleKind.INCUMBENT_CONSUMED -> old.copy(
                incumbentConsumedNanos = old.incumbentConsumedNanos ?: nanos, incumbentUses = old.incumbentUses + 1)
            PortfolioLifecycleKind.RETIRED -> old.copy(retiredNanos = old.retiredNanos ?: nanos)
        }
    }

    fun disposed(arm: Int, nanos: Long) {
        lifecycle[arm] = lifecycle[arm].copy(retirementNanos = lifecycle[arm].retirementNanos + nanos)
    }

    fun active(arm: Int, nanos: Long) {
        lifecycle[arm] = lifecycle[arm].copy(activeNanos = lifecycle[arm].activeNanos + nanos)
    }

    fun phases(arm: Int): List<PortfolioPhaseStats> = phases[arm].filter { it.segments > 0 || it.credit.isNotEmpty() }
    fun lifecycle(arm: Int): PortfolioLifecycleStats = lifecycle[arm]
}
