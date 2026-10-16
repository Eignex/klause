package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

internal fun Problem.requiresExactObjectiveRoute(objective: LinearObjective?, stop: Cancellation): Boolean {
    if (objective == null) return false
    for (coefficient in objective.realCoefficientValues) {
        stop.charge(1)
        if (stop()) return false
        if (coefficient != 0.0) return false
    }
    for (weight in objective.boolWeightValues) {
        stop.charge(1)
        if (stop()) return false
        if (weight != 0L) return false
    }
    var lower = bigIntOf(objective.constant)
    var upper = lower
    for (variable in objective.intCoefficientValues.indices) {
        stop.charge(1)
        if (stop()) return false
        val coefficient = objective.intCoefficientValues[variable]
        if (coefficient == 0L) continue
        val lo = intBounds.lowerAsBigInteger(variable) ?: return true
        val hi = intBounds.upperAsBigInteger(variable) ?: return true
        val exactCoefficient = bigIntOf(coefficient)
        lower += exactCoefficient * if (coefficient > 0) lo else hi
        upper += exactCoefficient * if (coefficient > 0) hi else lo
    }
    val limit = bigIntOf(1L shl 53)
    return lower < -limit || upper > limit
}
