package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal data class OpenIncumbentValue(val source: BigFraction, val execution: BigFraction)

internal class OpenSourceObjective(
    private val execution: LinearObjective,
    val original: LinearObjective = execution,
    val maximize: Boolean = false,
    val context: SourceProofContext? = null,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    fun value(sample: Sample): OpenIncumbentValue? {
        val captured = context
        val exact = if (captured == null) {
            if (sample.exactReals == null && original.realCoefficientValues.any { it != 0.0 }) return null
            original.evaluateExact(sample)
        } else {
            captured.linearValue(sample, cancellation) ?: return null
        }
        return OpenIncumbentValue(exact, execution.evaluateExact(sample))
    }

    fun improves(candidate: BigFraction, standing: BigFraction): Boolean =
        if (maximize) candidate > standing else candidate < standing

    fun incumbents(): PortfolioIncumbents<OpenIncumbentValue> = PortfolioIncumbents(
        valueOf = { value(it.sample) },
        improves = { candidate, standing -> improves(candidate.source, standing.source) },
        approximateValue = { it.execution.toDouble() },
        gain = { standing, candidate ->
            (if (maximize) candidate.source - standing.source else standing.source - candidate.source).toDouble()
        },
    )

    fun minimizedSource(value: BigFraction): BigFraction = if (maximize) value.negated() else value

    fun completed(
        authority: LinearOptimizationResult,
        stop: Cancellation,
        stoppedReason: TerminationReason,
    ): OpenTheoryOptimum {
        val ray = authority.terminal?.claim as? SourceClaim.Ray ?: return authority.openOptimum()
        val pointValue = context?.linearValue(ray.sample, stop)
        if (pointValue != null) return OpenTheoryOptimum.Unbounded(OpenTheoryAssignment.Sampled(ray.sample),
            minimizedSource(pointValue), authority.stats, authority)
        val partial = LinearOptimizationResult(authority.scope, null, authority.bound, authority.incumbent,
            authority.value, if (stop()) stoppedReason else TerminationReason.Unsupported, authority.stats)
        return partial.openOptimum()
    }

    fun incomplete(result: MinimizeResult, incumbents: PortfolioIncumbents<OpenIncumbentValue>): OpenTheoryOptimum {
        val pooled = incumbents.exchange.current()
        val reason = when (result) {
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            else -> TerminationReason.BudgetExhausted
        }
        val authority = context?.let { LinearOptimizationResult(it.scope, null, null,
            pooled?.assignment, pooled?.objective?.source, reason, result.stats) }
        return OpenTheoryOptimum.Bounded(pooled?.assignment?.let { OpenTheoryAssignment.Sampled(it) },
            pooled?.objective?.source?.let(::minimizedSource), reason, result.stats, authority)
    }

    fun publish(sample: Sample, observer: SourcePublicationObserver) {
        val candidate = value(sample) ?: return
        val standing = (observer.latest?.value as? SourceValue.Rational)?.value
        if (standing == null || improves(candidate.source, standing)) {
            observer.accepted(sample, SourceValue.Rational(candidate.source))
        }
    }
}
