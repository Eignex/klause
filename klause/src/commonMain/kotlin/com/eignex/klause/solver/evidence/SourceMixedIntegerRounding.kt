package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun mixedIntegerRounding(
    row: SourceInequality, bounds: List<SourceInequality>, cancellation: Cancellation,
): SourceInequality? {
    val coefficients = row.coefficients
    if (coefficients.none { it.key.kind == SourceCoordinate.Kind.Integer } ||
        coefficients.any { (id, value) -> id.kind == SourceCoordinate.Kind.Boolean ||
            (id.kind == SourceCoordinate.Kind.Integer && value.den != BIG_ONE) }) return null
    val sides = linkedMapOf<SourceCoordinate, Pair<BigFraction, Boolean>>()
    var shifted = row.rhs
    for (bound in bounds) {
        if (cancellation()) return null
        val (id, coefficient) = bound.coefficients.entries.singleOrNull() ?: return null
        if (id.kind != SourceCoordinate.Kind.Real || id !in coefficients || id in sides) return null
        val endpoint = bound.rhs * coefficient.reciprocal()
        sides[id] = endpoint to (coefficient > BigFraction.ZERO)
        shifted -= coefficients.getValue(id) * endpoint
    }
    if (sides.isEmpty() || coefficients.keys.any { it.kind == SourceCoordinate.Kind.Real && it !in sides }) {
        return null
    }
    val floor = BigFraction.of(shifted.negated().ceilInteger(), BIG_ONE).negated()
    val fraction = shifted - floor
    if (fraction.isZero) return null
    val residualScale = (BigFraction.ONE - fraction).reciprocal()
    val result = coefficients.toMutableMap()
    var rhs = floor
    // After shifting, t - s <= floor(b) + f with integral t and s >= 0 implies
    // t - s / (1 - f) <= floor(b); positive continuous terms can be discarded.
    for ((id, side) in sides) {
        if (cancellation()) return null
        val value = coefficients.getValue(id)
        val negative = if (side.second) value > BigFraction.ZERO else value < BigFraction.ZERO
        val rounded = if (negative) value * residualScale else BigFraction.ZERO
        result[id] = rounded
        rhs += rounded * side.first
    }
    return SourceInequality(result, rhs)
}
