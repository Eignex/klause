package com.eignex.klause.backtrack

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.toLongExact

internal class BooleanObjectiveCutoff(val weights: LongArray, val literals: IntArray, val degree: Long)

internal fun booleanObjectiveCutoff(
    problem: Problem,
    objective: LinearObjective,
    incumbent: BigFraction,
): BooleanObjectiveCutoff? {
    if (problem.numRealVars > 0 || objective.boolWeightValues.size > problem.numBoolVars ||
        objective.intCoefficientValues.any { it != 0L } || objective.realCoefficientValues.any { it != 0.0 } ||
        incumbent.den != BIG_ONE
    ) return null
    val weights = objective.boolWeightValues
    var positive = 0L
    var magnitude = 0L
    var nonzero = 0
    for (weight in weights) {
        if (weight == 0L) continue
        nonzero++
        if (weight == Long.MIN_VALUE) return null
        val absolute = if (weight < 0L) -weight else weight
        if (absolute > Long.MAX_VALUE - magnitude) return null
        magnitude += absolute
        if (weight > 0L) positive += weight
    }
    if (nonzero == 0) return null
    val degree = bigIntOf(positive) - (incumbent.num - bigIntOf(objective.constant) - BIG_ONE)
    if (!degree.fitsLong() || degree <= bigIntOf(0)) return null
    val coefficients = LongArray(nonzero)
    val literals = IntArray(nonzero)
    var index = 0
    for (id in weights.indices) {
        val weight = weights[id]
        if (weight == 0L) continue
        coefficients[index] = if (weight < 0L) -weight else weight
        literals[index] = Lit.make(id, weight < 0L)
        index++
    }
    return BooleanObjectiveCutoff(coefficients, literals, degree.toLongExact())
}
