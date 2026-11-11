package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.LinearNumericalResult
import kotlin.math.nextDown
import kotlin.math.nextUp

/** Native global bound over the nominal source, in its original objective units. */
class LinearSourceBound internal constructor(
    /** Original-unit objective bound. */
    val value: BigFraction,
    /** Minimization supplies a lower bound; maximization supplies an upper bound. */
    val sense: ObjectiveSense,
    /** Whether every feasible nominal objective lies strictly beyond [value]. */
    val strict: Boolean,
)

internal class FiniteObjectiveBound(val evidence: SourceEvidence, val value: BigFraction)

internal fun finiteObjectiveBound(evidence: SourceEvidence?, mapping: SourceMapping?): FiniteObjectiveBound? {
    val claim = evidence?.claim as? SourceClaim.Bound ?: return null
    val value = (claim.value as? SourceValue.Rational)?.value ?: return null
    if (evidence.scope.condition != SourceCondition() ||
        evidence.scope.objective?.sense != ObjectiveSense.MINIMIZE ||
        mapping?.guarantees?.let { !it.objective || !it.reconstructability } == true
    ) return null
    return FiniteObjectiveBound(evidence, mapping?.objectiveAdjustment?.sourceValue(value) ?: value)
}

internal fun PreparedLinearSource.qualifyBound(
    evidence: SourceEvidence?,
    unrestricted: Boolean,
    finite: FiniteObjectiveBound? = null,
): LinearSourceBound? {
    val claim = evidence?.claim as? SourceClaim.Bound ?: return null
    val objective = view.source.objective ?: return null
    val expected = if (finite == null) objective.sense else ObjectiveSense.MINIMIZE
    if (!view.exact || !unrestricted || evidence.scope.condition != SourceCondition() ||
        evidence.scope.objective?.sense != expected
    ) return null
    var value = finite?.value ?: (claim.value as? SourceValue.Rational)?.value ?: return null
    if (finite != null && objective.sense == ObjectiveSense.MAXIMIZE) value = value.negated()
    return LinearSourceBound(value * view.objectiveScale.reciprocal(), objective.sense, claim.strict)
}

internal fun PreparedLinearSource.qualifyNumericalBound(
    numerical: LinearNumericalResult?, unrestricted: Boolean,
): Double? {
    val objective = view.source.objective ?: return null
    val native = numerical?.bound ?: return null
    if (!view.exact || !unrestricted || numerical.scope.condition != SourceCondition() ||
        numerical.scope.objective?.sense != objective.sense) return null
    val value = (BigFraction.ofDouble(native) ?: return null) * view.objectiveScale.reciprocal()
    val rounded = value.toDouble().takeIf { it.isFinite() } ?: return null
    val projected = BigFraction.ofDouble(rounded) ?: return null
    val conservative = if (objective.sense == ObjectiveSense.MINIMIZE) {
        if (projected <= value) rounded else rounded.nextDown()
    } else {
        if (projected >= value) rounded else rounded.nextUp()
    }
    return conservative.takeIf { it.isFinite() }
}
