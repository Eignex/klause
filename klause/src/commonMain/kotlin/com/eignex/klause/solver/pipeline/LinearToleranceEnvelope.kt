package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal fun LinearSourceProblem.absoluteToleranceEnvelope(stop: ExecutionControl): LinearSourceProblem? {
    val policy = semantics.effective.feasibilityTolerance
    require(semantics.effective.format == SourceFormat.MPS && policy != null) {
        "linear tolerance envelopes require an MPS source"
    }
    require(policy.relative == SemanticRational.ZERO) {
        "linear tolerance envelopes require zero relative feasibility tolerance"
    }
    if (stop()) return null
    if (policy.isExact) return this
    val slack = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
    fun shifted(value: SemanticRational?, upper: Boolean): SemanticRational? = value?.let {
        val bound = BigFraction.of(it.numerator, it.denominator) + if (upper) slack else slack.negated()
        SemanticRational.of(bound.num, bound.den)
    }
    val variables = variableValues.map {
        stop.charge(1L)
        if (stop()) return null
        it.copy(lower = shifted(it.lower, false), upper = shifted(it.upper, true))
    }
    val rows = rowValues.map {
        stop.charge(1L + it.termValues.size)
        if (stop()) return null
        LinearSourceRow(it.termValues, shifted(it.lower, false), shifted(it.upper, true), it.indicator)
    }
    if (stop()) return null
    // The widened sides already state the accepted set; a second feasibility band would enlarge it again.
    val interpretation = EffectiveSourceSemantics(semantics.declared,
        semantics.effective.copy(feasibilityTolerance = SourceTolerance()))
    return LinearSourceProblem(variables, rows, objective, interpretation)
}
