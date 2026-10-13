package com.eignex.klause.cli

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.LinearOptimizationResult

internal fun reportLinearOptimization(
    result: LinearOptimizationResult,
    render: (Sample) -> String,
    output: OutputProtocol,
    publication: ArithmeticPublication,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean,
) {
    val accepted = result.incumbent?.let { point ->
        result.value?.let { publication.publish(render(point), SourceValue.Rational(it)) } ?: false
    } ?: true
    val verdict = if (!accepted) {
        if (publication.solutions > 0L) Verdict.BEST_FOUND else Verdict.UNKNOWN
    } else when (result.terminal?.claim) {
        is SourceClaim.Optimal -> Verdict.OPTIMAL
        is SourceClaim.Limit -> Verdict.SATISFIABLE
        is SourceClaim.Ray, is SourceClaim.Unbounded -> Verdict.UNBOUNDED
        SourceClaim.Refutation -> Verdict.UNSATISFIABLE
        else -> if (result.incumbent != null) Verdict.BEST_FOUND else Verdict.UNKNOWN
    }
    output.onVerdictContext(VerdictContext(terminationReason = result.reason, completePool = completePool))
    if (accepted) output.onLinearResult(result)
    output.onComplete(verdict)
    if (statistics) {
        output.onStatistics(result.stats, result.stats.run.wallMs + routingElapsedMs, publication.solutions)
    }
}
