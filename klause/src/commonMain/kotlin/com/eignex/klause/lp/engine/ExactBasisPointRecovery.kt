package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal class ExactBasisPointRecovery(val primal: List<BigFraction>?, val violatedRow: Int?)

internal fun ExactBasisAuthority.violatedBasic(basics: List<BigFraction>): Int? {
    for (slot in headings.indices) {
        meter.charge()
        val bounds = model.exactBounds(headings[slot])
        for (upper in listOf(false, true)) {
            val side = (if (upper) bounds.upper else bounds.lower) ?: continue
            val sign = meter.compare(basics[slot], side.number.value)
            if ((if (upper) sign > 0 else sign < 0) || (sign == 0 && side.strict)) return slot
        }
    }
    return null
}

internal fun ExactBasisAuthority.strictPrimal(
    factors: ExactBasisFactorization,
    basics: List<BigFraction>,
    allSeats: Boolean,
): ExactBasisPointRecovery {
    meter.phase = ExactBasisPhase.PRIMAL
    meter.charge(model.numVars * 5L + model.m, model.numVars * 96L + model.m * 32L)
    val epsilon = MutableList(model.numVars) { BigFraction.ZERO }
    for (column in statuses.indices) {
        val bounds = model.exactBounds(column)
        epsilon[column] = when (statuses[column]) {
            VarStatus.AT_LOWER -> if (allSeats || bounds.lower?.strict == true) BigFraction.ONE else BigFraction.ZERO
            VarStatus.AT_UPPER ->
                if (allSeats || bounds.upper?.strict == true) BigFraction.MINUS_ONE else BigFraction.ZERO
            else -> BigFraction.ZERO
        }
    }
    if (epsilon.all { it.isZero }) return ExactBasisPointRecovery(null, violatedBasic(basics))
    val rhs = MutableList(model.m) { BigFraction.ZERO }
    for (column in epsilon.indices) {
        meter.charge()
        if (epsilon[column].isZero) continue
        for ((row, coefficient) in matrix[column]) {
            rhs[row] = meter.subtractProduct(rhs[row], coefficient, epsilon[column])
        }
    }
    val directions = solveExactBasis(factors, rhs, false, meter)
    val values = seats.toMutableList()
    val slots = IntArray(model.numVars) { -1 }
    for (slot in headings.indices) {
        val column = headings[slot]
        slots[column] = slot
        values[column] = basics[slot]
        epsilon[column] = directions[slot]
    }
    var step = BigFraction.ONE
    val two = BigFraction.ofLong(2L)
    for (column in values.indices) {
        meter.charge()
        val bounds = model.exactBounds(column)
        for (upper in listOf(false, true)) {
            val side = (if (upper) bounds.upper else bounds.lower) ?: continue
            val slack = if (upper) meter.subtract(side.number.value, values[column]) else
                meter.subtract(values[column], side.number.value)
            val slope = if (upper) meter.multiply(BigFraction.MINUS_ONE, epsilon[column]) else epsilon[column]
            val blocked = slope.signum() < 0 || (side.strict && slope.isZero)
            if (slack.signum() < 0 || (slack.isZero && blocked)) {
                return ExactBasisPointRecovery(null, slots[column].takeIf { it >= 0 })
            }
            if (slack.signum() > 0 && slope.signum() < 0) {
                val crossing = meter.divide(slack, meter.multiply(BigFraction.MINUS_ONE, slope))
                val limit = meter.divide(crossing, two)
                if (meter.compare(limit, step) < 0) step = limit
            }
        }
    }
    val primal = List(model.n) { column ->
        meter.add(meter.add(values[column], meter.multiply(step, epsilon[column])), origins[column])
    }
    return ExactBasisPointRecovery(primal, null)
}
