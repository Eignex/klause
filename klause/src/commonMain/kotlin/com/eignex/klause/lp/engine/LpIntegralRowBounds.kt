package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Int128
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl

internal fun integralRowBounds(
    state: LpExactState,
    row: Int,
    terms: List<LpRowBoundTerm>,
    meter: MixedCertificateMeter,
): List<LpRowBound>? {
    meter.step()
    val rhs = state.model.rhs(row).exactLong() ?: return null
    for (term in terms) {
        meter.step()
        if (term.integerCoefficient == null) return null
        if (state.model.column(term.column).integral &&
            state.model.column(term.column).origin.exactLong() == null) return null
    }
    val minimum = integralEndpointSum(state, terms, true, meter) ?: return null
    val maximum = integralEndpointSum(state, terms, false, meter) ?: return null
    meter.reserve(128L)
    val completed = ArrayList<LpRowBound>()
    for (term in terms) {
        meter.step()
        if (term.column >= state.model.n || !state.model.column(term.column).integral) continue
        for (direction in 0..1) {
            val minimizing = direction == 0
            val sum = if (minimizing) minimum else maximum
            val projection = integralRowProjection(state, rhs, term, sum, minimizing, meter) ?: return null
            val value = projection.value ?: continue
            meter.reserve(80L)
            completed.add(LpRowBound(state, row, term.column, projection.upper, value,
                projection.strict, minimizing, terms))
        }
    }
    return completed
}

private data class IntegralEndpointSum(val value: Int128, val missing: Int, val strict: Int)
private data class IntegralRowProjection(
    val value: BigFraction? = null,
    val upper: Boolean = false,
    val strict: Boolean = false,
)
private val EMPTY_INTEGRAL_ROW_PROJECTION = IntegralRowProjection()

private fun integralEndpointSum(
    state: LpExactState,
    terms: List<LpRowBoundTerm>,
    minimum: Boolean,
    meter: MixedCertificateMeter,
): IntegralEndpointSum? {
    meter.reserve(80L)
    val sum = Int128()
    var missing = 0
    var strict = 0
    for (term in terms) {
        meter.step()
        val coefficient = checkNotNull(term.integerCoefficient)
        val side = integralEndpoint(state, term.column, coefficient, minimum)
        if (side == null) {
            missing++
        } else {
            val endpoint = side.number.exactLong() ?: return null
            meter.step()
            sum.addProduct(coefficient, endpoint)
            if (sum.overflow) return null
            if (side.strict) strict++
        }
    }
    return IntegralEndpointSum(sum, missing, strict)
}

private fun integralEndpoint(state: LpExactState, column: Int, coefficient: Long, minimum: Boolean): ExactLpSide? {
    val bounds = state.model.column(column).bounds
    return if (minimum == (coefficient > 0L)) bounds.lower else bounds.upper
}

@Suppress("LongParameterList")
private fun integralRowProjection(
    state: LpExactState,
    rhs: Long,
    term: LpRowBoundTerm,
    sum: IntegralEndpointSum,
    minimum: Boolean,
    meter: MixedCertificateMeter,
): IntegralRowProjection? {
    meter.step()
    val coefficient = checkNotNull(term.integerCoefficient)
    val own = integralEndpoint(state, term.column, coefficient, minimum)
    if (sum.missing > if (own == null) 1 else 0) return EMPTY_INTEGRAL_ROW_PROJECTION
    meter.reserve(64L)
    val residual = Int128().apply {
        addLong(rhs)
        subtract(sum.value)
        if (own != null) addProduct(coefficient, own.number.exactLong() ?: return null)
    }
    if (residual.overflow) return null
    val upper = minimum == (coefficient > 0L)
    val strict = sum.strict > if (own?.strict == true) 1 else 0
    val bounds = state.model.column(term.column).bounds
    val previous = if (upper) bounds.upper else bounds.lower
    if (previous != null) {
        val tightens = integralProjectionTightens(residual, coefficient, previous, minimum, strict, meter)
            ?: return null
        if (!tightens) return EMPTY_INTEGRAL_ROW_PROJECTION
    }
    meter.reserve(128L)
    val exactResidual = meter.read(BigFraction.of(
        (bigIntOf(residual.hi) shl 64) + bigIntOf(residual.lo.toULong()), bigIntOf(1L),
    ))
    val value = meter.read(meter.read(exactResidual * meter.read(term.coefficient.reciprocal())) +
        meter.read(state.model.column(term.column).origin.value))
    meter.reserve(48L)
    return IntegralRowProjection(value, upper, strict)
}

@Suppress("LongParameterList")
private fun integralProjectionTightens(
    residual: Int128,
    coefficient: Long,
    previous: ExactLpSide,
    minimum: Boolean,
    strict: Boolean,
    meter: MixedCertificateMeter,
): Boolean? {
    val endpoint = previous.number.exactLong() ?: return null
    meter.reserve(96L)
    val contribution = Int128().apply { addProduct(coefficient, endpoint) }
    val difference = residual.copy().apply { subtract(contribution) }
    if (difference.overflow) return null
    val equal = difference.hi == 0L && difference.lo == 0L
    val weaker = if (minimum) difference.isNonNegative() && !equal else !difference.isNonNegative()
    return !weaker && !(equal && (!strict || previous.strict))
}
