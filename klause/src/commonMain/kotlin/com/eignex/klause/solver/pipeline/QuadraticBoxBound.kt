package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction

internal data class QuadraticBoxEvidence(val lower: BigFraction, val upper: BigFraction)

internal fun quadraticBoxBound(
    source: Problem,
    objective: QuadraticObjectiveSpec,
    assumptions: Assumptions,
    sense: ObjectiveSense,
    step: () -> Unit,
): Pair<BigFraction, QuadraticBoxEvidence>? {
    fun range(variable: QuadraticVariable): Pair<BigFraction, BigFraction>? = when (variable) {
        is QuadraticVariable.Bool -> assumptions.boolValueOrNull(variable.id.index)?.let {
            val value = if (it) BigFraction.ONE else BigFraction.ZERO
            value to value
        } ?: (BigFraction.ZERO to BigFraction.ONE)
        is QuadraticVariable.Int -> assumptions.intValueOrNull(variable.id.index)?.let {
            val value = BigFraction.ofLong(it)
            value to value
        } ?: variable.id.index.let { id ->
            if (!source.intBounds.hasLower(id) || !source.intBounds.hasUpper(id)) null
            else BigFraction.ofLong(source.intBounds.lower(id)) to BigFraction.ofLong(source.intBounds.upper(id))
        }
        is QuadraticVariable.Real -> variable.id.index.let { id ->
            val lower = BigFraction.ofDouble(source.realLower[id])
            val upper = BigFraction.ofDouble(source.realUpper[id])
            if (lower == null || upper == null) null else lower to upper
        }
    }
    fun fraction(value: QuadraticCoefficient) = BigFraction.of(value.numerator, value.denominator)
    var lower = fraction(objective.constant)
    var upper = lower
    for (term in objective.terms) {
        step()
        val coefficient = fraction(term.coefficient)
        if (coefficient == BigFraction.ZERO) continue
        val first = range(term.first) ?: return null
        if (first.first > first.second) return null
        val second = term.second?.let { range(it) ?: return null }
        if (second != null && second.first > second.second) return null
        val values = if (second == null) listOf(first.first, first.second)
        else if (term.first == term.second) {
            listOf(first.first * first.first, first.second * first.second) +
                if (first.first <= BigFraction.ZERO && first.second >= BigFraction.ZERO) listOf(BigFraction.ZERO)
                else emptyList()
        } else listOf(
            first.first * second.first, first.first * second.second,
            first.second * second.first, first.second * second.second,
        )
        val scaled = values.map { it * coefficient }
        lower += scaled.reduce { left, right -> if (left < right) left else right }
        upper += scaled.reduce { left, right -> if (left > right) left else right }
    }
    step()
    return (if (sense == ObjectiveSense.MINIMIZE) lower else upper) to QuadraticBoxEvidence(lower, upper)
}
