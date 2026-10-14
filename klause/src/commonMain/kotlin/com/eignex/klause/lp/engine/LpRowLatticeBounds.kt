package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.rem
import com.eignex.klause.util.toLongExact
import kotlin.math.abs

internal fun rowLatticeBounds(
    state: LpExactState,
    row: Int,
    terms: List<LpRowBoundTerm>,
    ordinary: List<LpRowBound>,
    meter: MixedCertificateMeter,
): List<LpRowBound> {
    if (state.model.n + row >= state.baseModel.numVars) return emptyList()
    val logical = state.model.column(state.model.n + row).bounds
    val lower = logical.lower ?: return emptyList()
    val upper = logical.upper ?: return emptyList()
    if (lower.strict || upper.strict || lower.premises != null || upper.premises != null ||
        lower.number.value != upper.number.value || logical != state.baseModel.column(state.model.n + row).bounds
    ) return emptyList()
    for (term in terms) {
        meter.step()
        val bounds = state.model.column(term.column).bounds
        if (bounds.lower == null || bounds.upper == null) return emptyList()
    }
    meter.reserve(terms.size.toLong() * 32L + 256L)
    val integers = ArrayList<LpRowBoundTerm>()
    var rhs = meter.add(state.model.rhs(row).value, lower.number.value.negated())
    var realMinimum = BigFraction.ZERO
    var realMaximum = BigFraction.ZERO
    var minimumStrict = false
    var maximumStrict = false
    for (term in terms) {
        meter.step()
        if (term.column >= state.model.n) continue
        val column = state.model.column(term.column)
        val lo = column.bounds.lower ?: return emptyList()
        val hi = column.bounds.upper ?: return emptyList()
        if (column.integral) {
            val coefficient = term.integerCoefficient ?: return emptyList()
            if (coefficient == Long.MIN_VALUE) return emptyList()
            integers.add(term)
            if (!column.origin.value.isZero) {
                rhs = meter.add(rhs, meter.multiply(term.coefficient, column.origin.value))
            }
        } else {
            val positive = term.coefficient.signum() > 0
            val minimizing = if (positive) lo else hi
            val maximizing = if (positive) hi else lo
            realMinimum = meter.add(realMinimum, meter.multiply(term.coefficient, minimizing.number.value))
            realMaximum = meter.add(realMaximum, meter.multiply(term.coefficient, maximizing.number.value))
            minimumStrict = minimumStrict || minimizing.strict
            maximumStrict = maximumStrict || maximizing.strict
        }
    }
    if (integers.size < 2) return emptyList()
    meter.reserve(256L)
    val rangeLower = integerEndpoint(meter.add(rhs, realMaximum.negated()), maximumStrict, false) ?: return emptyList()
    val rangeUpper = integerEndpoint(meter.add(rhs, realMinimum.negated()), minimumStrict, true) ?: return emptyList()
    meter.reserve((integers.size.toLong() + 1L) * 16L + 192L)
    val prefix = LongArray(integers.size + 1)
    val suffix = LongArray(integers.size + 1)
    for (index in integers.indices) {
        prefix[index + 1] = rowGcd(prefix[index], abs(checkNotNull(integers[index].integerCoefficient)), meter)
    }
    for (index in integers.indices.reversed()) {
        suffix[index] = rowGcd(suffix[index + 1], abs(checkNotNull(integers[index].integerCoefficient)), meter)
    }
    val completed = ArrayList<LpRowBound>()
    for ((index, term) in integers.withIndex()) {
        meter.step()
        val gcd = rowGcd(prefix[index], suffix[index + 1], meter)
        if (gcd == 0L || (gcd == 1L && rangeLower <= rangeUpper)) continue
        if (rangeLower <= rangeUpper &&
            !meter.less(meter.add(BigFraction.ofLong(rangeUpper), BigFraction.ofLong(rangeLower).negated()),
                BigFraction.ofLong(gcd - 1L))
        ) continue
        val interval = targetInterval(state, term.column, ordinary, meter) ?: continue
        if (interval.first > interval.last ||
            meter.less(BigFraction.ofLong(15L),
                meter.add(BigFraction.ofLong(interval.last), BigFraction.ofLong(interval.first).negated()))
        ) continue
        meter.step()
        val allowedLower = positiveRemainder(rangeLower, gcd)
        meter.step()
        val allowedUpper = positiveRemainder(rangeUpper, gcd)
        var first: Long? = null
        var last: Long? = null
        var value = interval.first
        while (true) {
            meter.step()
            val residue = rowResidue(checkNotNull(term.integerCoefficient), value, gcd, meter)
            val allowed = rangeLower <= rangeUpper && if (allowedLower <= allowedUpper) {
                residue in allowedLower..allowedUpper
            } else {
                residue >= allowedLower || residue <= allowedUpper
            }
            if (allowed) {
                if (first == null) first = value
                last = value
            }
            if (value == interval.last) break
            value++
        }
        val lo = first ?: interval.last.takeIf { it != Long.MAX_VALUE }?.plus(1L)
        val hi = last ?: interval.first.takeIf { it != Long.MIN_VALUE }?.minus(1L)
        if (lo != null && lo > interval.first) {
            meter.reserve(160L)
            completed.add(LpRowBound(state, row, term.column, false, BigFraction.ofLong(lo),
                false, true, terms, lattice = true))
        }
        if (hi != null && hi < interval.last) {
            meter.reserve(160L)
            completed.add(LpRowBound(state, row, term.column, true, BigFraction.ofLong(hi),
                false, false, terms, lattice = true))
        }
    }
    return completed
}

private data class RowIntegerInterval(val first: Long, val last: Long)

private fun targetInterval(
    state: LpExactState,
    column: Int,
    ordinary: List<LpRowBound>,
    meter: MixedCertificateMeter,
): RowIntegerInterval? {
    val source = state.model.column(column)
    val lower = source.bounds.lower ?: return null
    val upper = source.bounds.upper ?: return null
    meter.reserve(256L)
    var first = integerEndpoint(meter.add(lower.number.value, source.origin.value), lower.strict, false) ?: return null
    var last = integerEndpoint(meter.add(upper.number.value, source.origin.value), upper.strict, true) ?: return null
    for (bound in ordinary) {
        meter.step()
        if (bound.column != column) continue
        meter.read(bound.value)
        meter.reserve(128L)
        val value = bound.integerBound() ?: continue
        if (bound.upper) last = minOf(last, value) else first = maxOf(first, value)
    }
    meter.reserve(32L)
    return RowIntegerInterval(first, last)
}

private fun integerEndpoint(value: BigFraction, strict: Boolean, upper: Boolean): Long? = when {
    upper && strict -> value.ceilLong()?.takeIf { it != Long.MIN_VALUE }?.minus(1L)
    upper -> value.floorLong()
    strict -> value.floorLong()?.takeIf { it != Long.MAX_VALUE }?.plus(1L)
    else -> value.ceilLong()
}

private fun rowGcd(left: Long, right: Long, meter: MixedCertificateMeter): Long {
    var a = left
    var b = right
    while (b != 0L) {
        meter.step()
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

private fun positiveRemainder(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}

private fun rowResidue(coefficient: Long, value: Long, divisor: Long, meter: MixedCertificateMeter): Long {
    meter.reserve(256L)
    val product = meter.multiply(BigFraction.ofLong(coefficient), BigFraction.ofLong(value))
    val remainder = (product.num % bigIntOf(divisor)).toLongExact()
    return if (remainder < 0L) remainder + divisor else remainder
}
