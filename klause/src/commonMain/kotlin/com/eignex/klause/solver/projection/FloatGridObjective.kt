package com.eignex.klause.solver.projection

import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class FloatGridObjective(
    val original: LinearObjective,
    val native: LinearObjective,
    val scale: BigFraction,
    val offset: BigFraction,
) {
    init { require(scale.signum() > 0) }

    fun sourceValue(nativeValue: Long, maximize: Boolean): BigFraction {
        val value = BigFraction.ofLong(nativeValue)
        return offset + scale * if (maximize) value.negated() else value
    }
}

internal fun FloatGridMapping.projectObjective(
    objective: LinearObjective,
    cancellation: Cancellation,
): FloatGridObjective? {
    val stop = guardedCancellation(cancellation)
    if (stop()) return null
    if (objective.boolWeightValues.size > source.numBoolVars ||
        objective.intCoefficientValues.size > source.numIntVars ||
        objective.realCoefficientValues.size > source.numRealVars ||
        objective.boolWeightValues.size.toLong() + target.numIntVars > Int.MAX_VALUE
    ) return null
    val booleans = objective.boolWeightValues.size
    val coefficients = Array(booleans + target.numIntVars) { BigFraction.ZERO }
    var offset = BigFraction.ofLong(objective.constant)
    for (id in objective.boolWeightValues.indices) {
        stop.charge(1)
        if (stop()) return null
        coefficients[id] = BigFraction.ofLong(objective.boolWeightValues[id])
    }
    for (id in objective.intCoefficientValues.indices) {
        stop.charge(1)
        if (stop()) return null
        coefficients[booleans + id] = BigFraction.ofLong(objective.intCoefficientValues[id])
    }
    for (id in objective.realCoefficientValues.indices) {
        stop.charge(1)
        if (stop()) return null
        val coefficient = BigFraction.ofDouble(objective.realCoefficientValues[id]) ?: return null
        if (coefficient.isZero) continue
        val grid = column(SourceRealId(id)) ?: return null
        val targetId = booleans + grid.coordinate.variable
        coefficients[targetId] += coefficient * grid.step
        offset += coefficient * grid.origin
    }
    val first = coefficients.firstOrNull { !it.isZero }
    val unit = first?.let { if (it.signum() < 0) it.negated() else it } ?: BigFraction.ONE
    val ratios = ArrayList<BigFraction>(coefficients.size)
    var denominator = BIG_ONE
    for (coefficient in coefficients) {
        stop.charge(1)
        if (stop()) return null
        val ratio = coefficient * unit.reciprocal()
        // These reduced ratios must fit every proportional signed-word objective.
        if (!ratio.num.fitsLong() || !ratio.den.fitsLong()) return null
        denominator = denominator / denominator.gcd(ratio.den) * ratio.den
        if (!denominator.fitsLong()) return null
        ratios += ratio
    }
    val boolWeights = LongArray(booleans)
    val intWeights = LongArray(target.numIntVars)
    for ((index, ratio) in ratios.withIndex()) {
        stop.charge(1)
        if (stop()) return null
        val value = ratio.num * (denominator / ratio.den)
        if (!value.fitsLong()) return null
        if (index < booleans) boolWeights[index] = value.toLongExact()
        else intWeights[index - booleans] = value.toLongExact()
    }
    if (!objectiveActivitiesFit(boolWeights, intWeights, stop)) return null
    if (stop()) return null
    return FloatGridObjective(objective, LinearObjective(boolWeights, intWeights),
        unit * BigFraction.of(denominator, BIG_ONE).reciprocal(), offset)
}

private fun FloatGridMapping.objectiveActivitiesFit(
    bools: LongArray,
    ints: LongArray,
    cancellation: Cancellation,
): Boolean {
    var lower = bigIntOf(0)
    var upper = bigIntOf(0)
    for (weight in bools) {
        cancellation.charge(1)
        if (cancellation()) return false
        lower += bigIntOf(minOf(0L, weight))
        upper += bigIntOf(maxOf(0L, weight))
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return false
        val weight = bigIntOf(ints[id])
        val first = weight * bigIntOf(target.intBounds.lower(id))
        val last = weight * bigIntOf(target.intBounds.upper(id))
        lower += if (first < last) first else last
        upper += if (first > last) first else last
    }
    return !cancellation() && lower.fitsLong() && upper.fitsLong()
}
