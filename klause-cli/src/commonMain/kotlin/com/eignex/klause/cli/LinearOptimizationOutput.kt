package com.eignex.klause.cli

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.SourceIncumbentPublication
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.LpStats

internal fun reportLinearOptimization(
    result: LinearOptimizationResult,
    render: (Sample) -> String,
    output: OutputProtocol,
    publication: ArithmeticPublication,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean,
    routingLpStats: LpStats = LpStats(),
    budgetExhausted: (Boolean) -> Boolean = { it },
    receipt: SourceIncumbentPublication? = null,
) {
    val stats = result.stats.copy(lp = result.stats.lp.mergedWith(routingLpStats))
    val point = receipt?.sample ?: result.incumbent
    val value = receipt?.value ?: result.value?.let { SourceValue.Rational(it) }
    val accepted = point?.let {
        value?.let { objective -> publication.publish(render(it), objective, receipt?.version) } ?: false
    } ?: true
    val verdict = if (!accepted) {
        if (publication.solutions > 0L) Verdict.BEST_FOUND else Verdict.UNKNOWN
    } else when (result.terminal?.claim) {
        is SourceClaim.Optimal -> Verdict.OPTIMAL
        is SourceClaim.Limit -> Verdict.BEST_FOUND
        is SourceClaim.Ray, is SourceClaim.Unbounded -> Verdict.UNBOUNDED
        SourceClaim.Refutation -> Verdict.UNSATISFIABLE
        else -> if (point != null) Verdict.BEST_FOUND else Verdict.UNKNOWN
    }
    output.onVerdictContext(VerdictContext(budgetExhausted = budgetExhausted(stats.run.timedOut),
        terminationReason = result.reason, completePool = completePool))
    if (accepted) output.onLinearResult(result)
    output.onComplete(verdict)
    if (statistics) {
        output.onStatistics(stats, stats.run.wallMs + routingElapsedMs, publication.solutions)
    }
}
