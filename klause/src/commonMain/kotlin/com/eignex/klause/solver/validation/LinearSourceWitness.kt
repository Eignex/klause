package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

/** Checked declaration-order coordinates and their original source objective value. */
class LinearSourceWitness internal constructor(
    /** Source whose original rows and tolerance accepted this point. */
    val source: LinearSourceProblem,
    values: List<BigFraction>,
    /** Exact original-unit objective value, or null for satisfaction. */
    val objective: BigFraction?,
    /** Whether every original bound and active row holds without tolerance. */
    val exact: Boolean,
) {
    private val coordinates = values.toList()

    /** Independent coordinate container in original declaration order. */
    val values: List<BigFraction> get() = coordinates.toList()
}

internal sealed interface LinearSourceCheck {
    class Accepted(val witness: LinearSourceWitness) : LinearSourceCheck
    data object Rejected : LinearSourceCheck
    data object Declined : LinearSourceCheck
}

internal fun checkLinearSourceWitness(
    source: LinearSourceProblem,
    values: List<BigFraction>,
    stop: Cancellation,
): LinearSourceCheck {
    if (stop()) return LinearSourceCheck.Declined
    if (values.size != source.variableValues.size) return LinearSourceCheck.Rejected
    val tolerance = source.semantics.effective.feasibilityTolerance ?: SourceTolerance()
    val absolute = BigFraction.of(tolerance.absolute.numerator, tolerance.absolute.denominator)
    val relative = BigFraction.of(tolerance.relative.numerator, tolerance.relative.denominator)
    var exact = true
    fun slack(bound: BigFraction, magnitude: BigFraction): BigFraction {
        val boundMagnitude = bound.magnitude()
        val scaled = relative * if (boundMagnitude > magnitude) boundMagnitude else magnitude
        return if (absolute > scaled) absolute else scaled
    }
    fun withinTolerance(value: BigFraction, lower: BigFraction?, upper: BigFraction?, magnitude: BigFraction): Boolean {
        return (lower == null || value >= lower - slack(lower, magnitude)) &&
            (upper == null || value <= upper + slack(upper, magnitude))
    }
    for ((index, variable) in source.variableValues.withIndex()) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        val value = values[index]
        if (variable.integer && value.den != BIG_ONE) return LinearSourceCheck.Rejected
        val lower = variable.lower?.fraction()
        val upper = variable.upper?.fraction()
        if (!withinBounds(value, lower, upper)) {
            if (!withinTolerance(value, lower, upper, BigFraction.ZERO)) return LinearSourceCheck.Rejected
            exact = false
        }
    }
    for (row in source.rowValues) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        val guard = row.indicator
        val trigger = if (guard?.whenOne == true) BigFraction.ONE else BigFraction.ZERO
        if (guard != null && values[guard.column] != trigger) continue
        var activity = BigFraction.ZERO
        for (term in row.termValues) {
            stop.charge(1L)
            if (stop()) return LinearSourceCheck.Declined
            val value = term.coefficient.fraction() * values[term.column]
            activity += value
        }
        val lower = row.lower?.fraction()
        val upper = row.upper?.fraction()
        if (!withinBounds(activity, lower, upper)) {
            val magnitude = if (relative == BigFraction.ZERO) BigFraction.ZERO else {
                row.activityMagnitude(values, stop) ?: return LinearSourceCheck.Declined
            }
            if (!withinTolerance(activity, lower, upper, magnitude)) return LinearSourceCheck.Rejected
            exact = false
        }
    }
    val objective = source.objective?.let { objective ->
        var value = objective.constant.fraction()
        for (term in objective.termValues) {
            stop.charge(1L)
            if (stop()) return LinearSourceCheck.Declined
            value += term.coefficient.fraction() * values[term.column]
        }
        value
    }
    return if (stop()) {
        LinearSourceCheck.Declined
    } else {
        LinearSourceCheck.Accepted(LinearSourceWitness(source, values, objective, exact))
    }
}

private fun withinBounds(value: BigFraction, lower: BigFraction?, upper: BigFraction?): Boolean =
    (lower == null || value >= lower) && (upper == null || value <= upper)

private fun LinearSourceRow.activityMagnitude(values: List<BigFraction>, stop: Cancellation): BigFraction? {
    var magnitude = BigFraction.ZERO
    for (term in termValues) {
        stop.charge(1L)
        if (stop()) return null
        magnitude += (term.coefficient.fraction() * values[term.column]).magnitude()
    }
    return magnitude
}

private fun BigFraction.magnitude(): BigFraction = if (this < BigFraction.ZERO) negated() else this

private fun SemanticRational.fraction(): BigFraction = BigFraction.of(numerator, denominator)
