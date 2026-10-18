package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
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
    fun reject(): LinearSourceCheck = if (stop()) LinearSourceCheck.Declined else LinearSourceCheck.Rejected
    if (values.size != source.variableValues.size) return reject()
    var visits = 0
    fun stoppedAfterVisit(): Boolean {
        stop.charge(1L)
        visits++
        return (stop.workMeter() != null || visits % SOURCE_POINT_POLL_BATCH == 0) && stop()
    }
    val tolerance = source.semantics.effective.feasibilityTolerance ?: SourceTolerance()
    val absolute = BigFraction.of(tolerance.absolute.numerator, tolerance.absolute.denominator)
    val relative = BigFraction.of(tolerance.relative.numerator, tolerance.relative.denominator)
    var exact = true
    fun slack(bound: BigFraction, magnitude: BigFraction): BigFraction {
        val boundMagnitude = bound.magnitude()
        val scaled = relative * if (boundMagnitude > magnitude) boundMagnitude else magnitude
        return if (absolute > scaled) absolute else scaled
    }
    fun satisfies(value: BigFraction, lower: BigFraction?, upper: BigFraction?, magnitude: BigFraction): Boolean {
        if ((lower != null && value < lower) || (upper != null && value > upper)) exact = false
        return (lower == null || value >= lower - slack(lower, magnitude)) &&
            (upper == null || value <= upper + slack(upper, magnitude))
    }
    for ((index, variable) in source.variableValues.withIndex()) {
        if (stoppedAfterVisit()) return LinearSourceCheck.Declined
        val value = values[index]
        if (variable.integer && value.den != BIG_ONE) return reject()
        if (!satisfies(value, variable.lower?.fraction(), variable.upper?.fraction(), BigFraction.ZERO)) {
            return reject()
        }
    }
    for (row in source.rowValues) {
        if (stoppedAfterVisit()) return LinearSourceCheck.Declined
        val guard = row.indicator
        val trigger = if (guard?.whenOne == true) BigFraction.ONE else BigFraction.ZERO
        if (guard != null && values[guard.column] != trigger) continue
        var activity = BigFraction.ZERO
        var magnitude = BigFraction.ZERO
        for (term in row.termValues) {
            if (stoppedAfterVisit()) return LinearSourceCheck.Declined
            val value = term.coefficient.fraction() * values[term.column]
            activity += value
            magnitude += value.magnitude()
        }
        if (!satisfies(activity, row.lower?.fraction(), row.upper?.fraction(), magnitude)) {
            return reject()
        }
    }
    val objective = source.objective?.let { objective ->
        var value = objective.constant.fraction()
        for (term in objective.termValues) {
            if (stoppedAfterVisit()) return LinearSourceCheck.Declined
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

private const val SOURCE_POINT_POLL_BATCH = 64

private fun BigFraction.magnitude(): BigFraction = if (this < BigFraction.ZERO) negated() else this

private fun SemanticRational.fraction(): BigFraction = BigFraction.of(numerator, denominator)
