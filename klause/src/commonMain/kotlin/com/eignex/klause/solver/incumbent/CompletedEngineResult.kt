package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason

internal sealed interface CompletedEngineResult {
    val scope: SourceScope
    val evidence: SourceResultEvidence?
    fun attests(kind: EvidenceKind): Boolean

    data class Solve(override val scope: SourceScope, val result: SolveResult) : CompletedEngineResult {
        override val evidence: SourceResultEvidence? get() = result.evidence
        override fun attests(kind: EvidenceKind): Boolean = when (kind) {
            EvidenceKind.Witness -> result is SolveResult.Sat
            EvidenceKind.Infeasible -> result is SolveResult.Unsat
            EvidenceKind.Bound, EvidenceKind.Unbounded -> false
        }
    }

    data class Minimize(override val scope: SourceScope, val result: MinimizeResult) : CompletedEngineResult {
        override val evidence: SourceResultEvidence? get() = result.evidence
        override fun attests(kind: EvidenceKind): Boolean = when (kind) {
            EvidenceKind.Witness -> result is MinimizeResult.WithSample
            EvidenceKind.Infeasible -> result is MinimizeResult.Infeasible
            EvidenceKind.Unbounded -> result is MinimizeResult.Unbounded
            EvidenceKind.Bound -> when (result) {
                is MinimizeResult.Optimal -> true
                is MinimizeResult.BestFound -> result.reason == TerminationReason.SearchExhausted
                is MinimizeResult.Unknown -> result.reason == TerminationReason.SearchExhausted
                else -> false
            }
        }
    }
}
