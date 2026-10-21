package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal class SparseExactBasisRecovery(
    private val authority: ExactBasisAuthority,
    private var factors: ExactBasisFactorization,
) {
    private val model = authority.model
    private val meter = authority.meter
    private var headings: IntArray
    private val statuses: Array<VarStatus>
    private val seats: MutableList<BigFraction>
    private val seatEpsilon: MutableList<BigFraction>
    private val bounds: List<ExactLpBounds>
    private val strict: Boolean
    var pivots = 0
        private set

    init {
        meter.charge(model.numVars * 5L + model.m, model.numVars * 160L + model.m * 16L)
        headings = authority.headings.copyOf()
        statuses = authority.statuses.copyOf()
        seats = authority.seats.toMutableList()
        bounds = List(model.numVars) { model.exactBounds(it) }
        seatEpsilon = MutableList(model.numVars) { column ->
            endpointEpsilon(column, statuses[column] == VarStatus.AT_UPPER).takeIf {
                statuses[column] == VarStatus.AT_LOWER || statuses[column] == VarStatus.AT_UPPER
            } ?: BigFraction.ZERO
        }
        strict = bounds.any { it.lower?.strict == true || it.upper?.strict == true }
    }

    fun recover(): ExactBasisPointRecovery {
        while (true) {
            meter.phase = ExactBasisPhase.FEASIBILITY
            val values = solveValues(epsilon = false)
            val epsilon = if (strict) solveValues(epsilon = true) else {
                meter.charge(bytes = model.numVars * 8L)
                List(model.numVars) { BigFraction.ZERO }
            }
            var row = -1
            var direction = 0
            for (candidate in headings.indices) {
                meter.charge()
                if (row >= 0 && headings[candidate] >= headings[row]) continue
                val violated = violation(headings[candidate], values, epsilon)
                if (violated != 0) {
                    row = candidate
                    direction = violated
                }
            }
            if (row < 0) return authority.materializeBasisPoint(values, epsilon)
            meter.charge(model.m.toLong(), model.m * 16L)
            val seed = List(model.m) { if (it == row) BigFraction.ONE else BigFraction.ZERO }
            val ray = solveExactBasis(factors, seed, true, meter)
            val enter = entering(ray, direction)
                ?: return ExactBasisPointRecovery(null, row, ray)
            if (pivots >= meter.limits.maxRecoveryPivots) meter.stop(ExactBasisDecline.PIVOTS)
            meter.charge(model.m.toLong(), model.m * 4L)
            val next = headings.copyOf()
            next[row] = enter
            meter.phase = ExactBasisPhase.FACTOR
            val nextFactors = buildSparseExactBasis(authority, next)
            val leaving = headings[row]
            val upper = direction < 0
            val side = requireNotNull(if (upper) bounds[leaving].upper else bounds[leaving].lower)
            // The zero-cost feasibility problem uses Bland's smallest leaving and entering columns.
            seats[leaving] = side.number.value
            seatEpsilon[leaving] = endpointEpsilon(leaving, upper)
            statuses[leaving] = when {
                bounds[leaving].fixed -> VarStatus.FIXED
                upper -> VarStatus.AT_UPPER
                else -> VarStatus.AT_LOWER
            }
            seats[enter] = BigFraction.ZERO
            seatEpsilon[enter] = BigFraction.ZERO
            statuses[enter] = VarStatus.BASIC
            headings = next
            factors = nextFactors
            pivots++
        }
    }

    private fun endpointEpsilon(column: Int, upper: Boolean): BigFraction {
        val side = if (upper) bounds[column].upper else bounds[column].lower
        return if (side?.strict != true) BigFraction.ZERO else {
            if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
        }
    }

    private fun solveValues(epsilon: Boolean): List<BigFraction> {
        meter.charge(model.m.toLong(), model.m * 16L + model.numVars * 16L)
        val endpoints = if (epsilon) seatEpsilon else seats
        val rhs = if (epsilon) MutableList(model.m) { BigFraction.ZERO } else authority.rhs.toMutableList()
        for (column in endpoints.indices) {
            meter.charge()
            if (statuses[column] == VarStatus.BASIC || endpoints[column].isZero) continue
            for ((row, coefficient) in authority.matrix[column]) {
                meter.charge()
                rhs[row] = meter.subtractProduct(rhs[row], coefficient, endpoints[column])
            }
        }
        val basics = solveExactBasis(factors, rhs, false, meter)
        val result = endpoints.toMutableList()
        for (slot in headings.indices) {
            meter.charge()
            result[headings[slot]] = basics[slot]
        }
        return result
    }

    private fun compareSide(column: Int, value: BigFraction, epsilon: BigFraction, upper: Boolean): Int {
        val side = requireNotNull(if (upper) bounds[column].upper else bounds[column].lower)
        val comparison = meter.compare(value, side.number.value)
        return if (comparison == 0) meter.compare(epsilon, endpointEpsilon(column, upper)) else comparison
    }

    private fun violation(column: Int, values: List<BigFraction>, epsilon: List<BigFraction>): Int {
        if (bounds[column].lower != null && compareSide(column, values[column], epsilon[column], false) < 0) return 1
        if (bounds[column].upper != null && compareSide(column, values[column], epsilon[column], true) > 0) return -1
        return 0
    }

    private fun entering(ray: List<BigFraction>, direction: Int): Int? {
        for (column in statuses.indices) {
            meter.charge()
            if (statuses[column] == VarStatus.BASIC) continue
            var coefficient = BigFraction.ZERO
            for ((row, value) in authority.matrix[column]) {
                meter.charge()
                if (!ray[row].isZero) coefficient = meter.add(coefficient, meter.multiply(ray[row], value))
            }
            val sign = coefficient.signum()
            if (sign == 0) continue
            val upper = -sign == direction
            val side = if (upper) bounds[column].upper else bounds[column].lower
            if (side == null) return column
            val comparison = compareSide(column, seats[column], seatEpsilon[column], upper)
            if (if (upper) comparison < 0 else comparison > 0) return column
        }
        return null
    }
}
