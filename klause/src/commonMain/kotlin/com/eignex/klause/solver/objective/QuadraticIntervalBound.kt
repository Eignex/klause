package com.eignex.klause.solver.objective

import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.simplex.exact.BigFraction

internal fun QuadraticObjectiveSpec.intervalBounds(
    range: (QuadraticVariable) -> Pair<BigFraction, BigFraction>?,
    step: () -> Unit,
): Pair<BigFraction, BigFraction>? {
    var lower = BigFraction.of(constant.numerator, constant.denominator)
    var upper = lower
    for (term in terms) {
        step()
        val coefficient = BigFraction.of(term.coefficient.numerator, term.coefficient.denominator)
        if (coefficient.isZero) continue
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
        val scaled = values.map { step(); it * coefficient }
        lower += scaled.reduce { left, right -> if (left < right) left else right }
        upper += scaled.reduce { left, right -> if (left > right) left else right }
    }
    step()
    return lower to upper
}
