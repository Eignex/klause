package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal enum class IntegerPinStatus { Established, Inactive, Unresolved }

internal class SourceIntegerBounds(
    private val lower: Map<SourceCoordinate, BigFraction>,
    private val upper: Map<SourceCoordinate, BigFraction>,
) {
    fun status(pins: Map<SourceCoordinate, BigFraction>): IntegerPinStatus {
        if (pins.any { (id, value) -> lower[id]?.let { it > value } == true ||
                upper[id]?.let { it < value } == true }) return IntegerPinStatus.Inactive
        return if (pins.all { (id, value) -> lower[id]?.let { it >= value } == true &&
                upper[id]?.let { it <= value } == true }) IntegerPinStatus.Established
            else IntegerPinStatus.Unresolved
    }

    fun split(pins: Map<SourceCoordinate, BigFraction>): Pair<SourceCoordinate, BigFraction>? {
        if (status(pins) != IntegerPinStatus.Unresolved) return null
        val (id, value) = pins.entries.first { (id, value) ->
            lower[id]?.let { it >= value } != true || upper[id]?.let { it <= value } != true
        }
        return id to if (lower[id]?.let { it >= value } == true) value else value - BigFraction.ONE
    }
}

internal fun SourceProofContext.integerPinBounds(
    condition: SourceCondition, cancellation: Cancellation,
): SourceIntegerBounds? {
    val lower = linkedMapOf<SourceCoordinate, BigFraction>()
    val upper = linkedMapOf<SourceCoordinate, BigFraction>()
    fun bound(id: SourceCoordinate, value: BigFraction, above: Boolean, strict: Boolean = false) {
        if (id.kind != SourceCoordinate.Kind.Integer) return
        val ceiling = BigFraction.of(value.ceilInteger(), BIG_ONE)
        val floor = BigFraction.of(value.negated().ceilInteger(), BIG_ONE).negated()
        val rounded = if (above) {
            if (strict) ceiling - BigFraction.ONE else floor
        } else if (strict) floor + BigFraction.ONE else ceiling
        val bounds = if (above) upper else lower
        val previous = bounds[id]
        if (previous == null || if (above) rounded < previous else rounded > previous) bounds[id] = rounded
    }
    for ((above, bounds) in listOf(false to condition.lower, true to condition.upper)) {
        for ((id, value) in bounds) {
            if (cancellation()) return null
            bound(id, value, above)
        }
    }
    // Conditional rows cannot establish their own pins or activate each other circularly.
    for (premise in premises.values) {
        if (cancellation()) return null
        if (premise.integerPins.isNotEmpty() ||
            premise.guards.any { (id, value) -> condition.bools[id] != value }) continue
        val row = premise.inequality
        if (row.coefficients.size != 1) continue
        val (id, coefficient) = row.coefficients.entries.single()
        if (!coefficient.isZero) bound(id, row.rhs / coefficient, coefficient > BigFraction.ZERO, row.strict)
    }
    return if (cancellation()) null else SourceIntegerBounds(lower, upper)
}
