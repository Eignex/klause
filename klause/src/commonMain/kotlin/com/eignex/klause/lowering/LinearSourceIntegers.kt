package com.eignex.klause.lowering

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong

internal fun LinearSourceProblem.integralRealBounds(
    stop: Cancellation,
): Array<Pair<BigFraction, BigFraction>?>? {
    if (stop()) return null
    val integral = BooleanArray(variableValues.size)
    val lower = arrayOfNulls<BigFraction>(variableValues.size)
    val upper = arrayOfNulls<BigFraction>(variableValues.size)
    val result = arrayOfNulls<Pair<BigFraction, BigFraction>>(variableValues.size)
    for ((id, variable) in variableValues.withIndex()) {
        stop.charge(1L)
        if (stop()) return null
        integral[id] = variable.integer
        lower[id] = variable.lower?.fraction()?.integerCeiling()
        upper[id] = variable.upper?.fraction()?.integerFloor()
    }
    val definitions = ArrayList<Pair<Map<Int, BigFraction>, BigFraction>>()
    for (row in rowValues) {
        stop.charge(1L)
        if (stop()) return null
        if (row.indicator != null || row.lower == null || row.lower != row.upper) continue
        val terms = linkedMapOf<Int, BigFraction>()
        for (term in row.termValues) {
            stop.charge(1L)
            if (stop()) return null
            val sum = (terms[term.column] ?: BigFraction.ZERO) + term.coefficient.fraction()
            if (sum.isZero) terms.remove(term.column) else terms[term.column] = sum
        }
        definitions += terms to row.lower.fraction()
    }
    do {
        var changed = false
        for ((terms, bound) in definitions) {
            stop.charge(1L)
            if (stop()) return null
            var target = -1
            var multiple = false
            for (column in terms.keys) {
                stop.charge(1L)
                if (stop()) return null
                if (!integral[column]) {
                    if (target >= 0) multiple = true else target = column
                }
            }
            if (target < 0 || multiple) continue
            val coefficient = requireNotNull(terms[target])
            val constant = bound / coefficient
            if (constant.den != BIG_ONE) continue
            var minimum = constant
            var maximum = constant
            var supported = true
            for ((column, value) in terms) {
                stop.charge(1L)
                if (stop()) return null
                if (column == target) continue
                val weight = (value / coefficient).negated()
                val lo = lower[column]
                val hi = upper[column]
                if (weight.den != BIG_ONE || lo == null || hi == null) {
                    supported = false
                    break
                }
                val first = weight * lo
                val second = weight * hi
                minimum += minOf(first, second)
                maximum += maxOf(first, second)
            }
            if (!supported) continue
            minimum = lower[target]?.let { maxOf(minimum, it) } ?: minimum
            maximum = upper[target]?.let { minOf(maximum, it) } ?: maximum
            if (!minimum.num.fitsLong() || !maximum.num.fitsLong()) continue
            integral[target] = true
            lower[target] = minimum
            upper[target] = maximum
            result[target] = minimum to maximum
            changed = true
        }
    } while (changed)
    return result.takeUnless { stop() }
}

private fun SemanticRational.fraction(): BigFraction = BigFraction.of(numerator, denominator)
private fun BigFraction.integerCeiling(): BigFraction = BigFraction.of(ceilInteger(), BIG_ONE)
private fun BigFraction.integerFloor(): BigFraction = negated().integerCeiling().negated()
