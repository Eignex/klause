package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

private fun bounded(value: BigFraction): BigFraction {
    if (value.num.magnitudeBitLength() > 4096 || value.den.magnitudeBitLength() > 4096) throw SourceRoundingLimit()
    return value
}

internal fun mixedIntegerRounding(
    row: SourceInequality, bounds: List<SourceInequality>, cancellation: Cancellation,
    onArithmetic: (BigFraction, BigFraction) -> Unit = { _, _ -> },
): SourceInequality? {
    val coefficients = row.coefficients
    for (value in coefficients.values) {
        if (cancellation()) return null
        bounded(value)
    }
    if (coefficients.none { it.key.kind == SourceCoordinate.Kind.Integer } ||
        coefficients.any { (id, value) -> id.kind == SourceCoordinate.Kind.Boolean ||
            (id.kind == SourceCoordinate.Kind.Integer && value.den != BIG_ONE) }) return null
    val sides = linkedMapOf<SourceCoordinate, Pair<BigFraction, Boolean>>()
    var shifted = bounded(row.rhs)
    for (bound in bounds) {
        if (cancellation()) return null
        val (id, coefficient) = bound.coefficients.entries.singleOrNull() ?: return null
        if (id.kind != SourceCoordinate.Kind.Real || id !in coefficients || id in sides) return null
        onArithmetic(BigFraction.ONE, bounded(coefficient))
        val reciprocal = coefficient.reciprocal()
        onArithmetic(bounded(bound.rhs), reciprocal)
        val endpoint = bounded(bound.rhs * reciprocal)
        sides[id] = endpoint to (coefficient > BigFraction.ZERO)
        onArithmetic(coefficients.getValue(id), endpoint)
        val contribution = bounded(coefficients.getValue(id) * endpoint)
        onArithmetic(shifted, contribution)
        shifted = bounded(shifted - contribution)
    }
    if (sides.isEmpty() || coefficients.keys.any { it.kind == SourceCoordinate.Kind.Real && it !in sides }) {
        return null
    }
    onArithmetic(shifted, BigFraction.ONE)
    val floor = BigFraction.of(shifted.negated().ceilInteger(), BIG_ONE).negated()
    onArithmetic(shifted, floor)
    val fraction = shifted - floor
    if (fraction.isZero) return null
    onArithmetic(BigFraction.ONE, fraction)
    val complement = BigFraction.ONE - fraction
    onArithmetic(BigFraction.ONE, complement)
    val residualScale = bounded(complement.reciprocal())
    val result = coefficients.toMutableMap()
    var rhs = floor
    // After shifting, t - s <= floor(b) + f with integral t and s >= 0 implies
    // t - s / (1 - f) <= floor(b); positive continuous terms can be discarded.
    for ((id, side) in sides) {
        if (cancellation()) return null
        val value = coefficients.getValue(id)
        val negative = if (side.second) value > BigFraction.ZERO else value < BigFraction.ZERO
        val rounded = if (negative) {
            onArithmetic(value, residualScale)
            bounded(value * residualScale)
        } else BigFraction.ZERO
        result[id] = rounded
        onArithmetic(rounded, side.first)
        val contribution = bounded(rounded * side.first)
        onArithmetic(rhs, contribution)
        rhs = bounded(rhs + contribution)
    }
    return SourceInequality(result, rhs)
}
