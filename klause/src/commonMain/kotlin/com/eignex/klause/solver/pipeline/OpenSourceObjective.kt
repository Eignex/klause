package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE

internal data class OpenIncumbentValue(val source: BigFraction, val execution: BigFraction)

internal class OpenSourceObjective(
    private val execution: LinearObjective,
    val original: LinearObjective = execution,
    val maximize: Boolean = false,
    val context: SourceProofContext? = null,
) {
    fun value(sample: Sample): OpenIncumbentValue? {
        val captured = context
        val exact = if (captured == null) {
            if (sample.exactReals == null && original.realCoefficientValues.any { it != 0.0 }) return null
            original.evaluateExact(sample)
        } else {
            if (sample.exactReals == null && captured.objective.keys.any {
                    it.kind == SourceCoordinate.Kind.Real
                }
            ) return null
            captured.objective.entries.fold(captured.objectiveConstant) { sum, (coordinate, weight) ->
                val scalar = when (coordinate.kind) {
                    SourceCoordinate.Kind.Boolean -> if (sample.boolValue(coordinate.id)) BigFraction.ONE
                        else BigFraction.ZERO
                    SourceCoordinate.Kind.Integer -> BigFraction.of(sample.exactIntValue(coordinate.id), BIG_ONE)
                    SourceCoordinate.Kind.Real -> requireNotNull(sample.exactReals)[coordinate.id]
                }
                sum + weight * scalar
            }
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

    fun publish(sample: Sample, observer: SourcePublicationObserver) {
        val candidate = value(sample) ?: return
        val standing = (observer.latest?.value as? SourceValue.Rational)?.value
        if (standing == null || improves(candidate.source, standing)) {
            observer.accepted(sample, SourceValue.Rational(candidate.source))
        }
    }
}
