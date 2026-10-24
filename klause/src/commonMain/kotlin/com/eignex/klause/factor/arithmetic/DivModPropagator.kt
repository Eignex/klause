package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class DivModPropagator(private val relation: DivMod) : Propagator {
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, relation.intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val reason = collectHoleAndBoundAntecedents(state, relation.intVars)
        val divisor = relation.divisor
        if (divisor != null && !state.excludeIntValue(divisor, 0L, reason)) return false
        val a = state.intDomains[relation.dividend]
        val b = divisor?.let { state.intDomains[it] }
        val constant = relation.constantDivisor ?: b?.takeIf { it.min == it.max }?.min?.let(::bigIntOf)
        val magnitude = constant?.abs() ?: maxOf(bigIntOf(checkNotNull(b).min).abs(), bigIntOf(b.max).abs())
        if (magnitude == BIG_ZERO) return false
        val remainderLimit = magnitude - BIG_ONE
        val nonnegative = relation.convention == DivisionConvention.EUCLIDEAN || a.min >= 0L
        val nonpositive = relation.convention == DivisionConvention.TRUNCATING && a.max <= 0L
        if (!tighten(state, relation.remainder, if (nonnegative) BIG_ZERO else BIG_ZERO - remainderLimit,
                if (nonpositive) BIG_ZERO else remainderLimit, reason)) return false
        // Within each nonzero divisor sign, quotient extrema lie at operand interval endpoints.
        val divisors = if (constant != null) listOf(constant) else {
            val domain = checkNotNull(b)
            val endpoints = arrayListOf(domain.min, domain.max)
            if (domain.min <= -1L) endpoints += kotlin.math.min(domain.max, -1L)
            if (domain.max >= 1L) endpoints += kotlin.math.max(domain.min, 1L)
            endpoints.filter { it != 0L }.distinct().map(::bigIntOf)
        }
        val quotients = divisors.flatMap { value ->
            listOf(checkNotNull(relation.outputs(bigIntOf(a.min), value)).first,
                checkNotNull(relation.outputs(bigIntOf(a.max), value)).first)
        }
        val lowerQ = quotients.reduce { first, second -> minOf(first, second) }
        val upperQ = quotients.reduce { first, second -> maxOf(first, second) }
        if (!tighten(state, relation.quotient, lowerQ, upperQ, reason)) return false
        val q = state.intDomains[relation.quotient]
        val r = state.intDomains[relation.remainder]
        val products = divisors.flatMap { value -> listOf(value * bigIntOf(q.min), value * bigIntOf(q.max)) }
        val lowerProduct = products.reduce { first, second -> minOf(first, second) }
        val upperProduct = products.reduce { first, second -> maxOf(first, second) }
        if (!tighten(state, relation.dividend, lowerProduct + bigIntOf(r.min),
                upperProduct + bigIntOf(r.max), reason)) return false
        val currentA = state.intDomains[relation.dividend]
        if (!tighten(state, relation.remainder, bigIntOf(currentA.min) - upperProduct,
                bigIntOf(currentA.max) - lowerProduct, reason)) return false
        if (divisor != null && !filterDivisor(state, divisor, reason)) return false
        return filterTuples(state, factorId, reason)
    }

    private fun filterDivisor(state: PropagationState, divisor: Int, reason: IntArray?): Boolean {
        val currentR = state.intDomains[relation.remainder]
        val minimumMagnitude = when {
            currentR.min > 0L -> bigIntOf(currentR.min)
            currentR.max < 0L -> BIG_ZERO - bigIntOf(currentR.max)
            else -> BIG_ZERO
        }
        val currentB = state.intDomains[divisor]
        if (currentB.min > 0L && !tighten(state, divisor, minimumMagnitude + BIG_ONE,
                bigIntOf(currentB.max), reason)) return false
        if (currentB.max < 0L && !tighten(state, divisor, bigIntOf(currentB.min),
                BIG_ZERO - minimumMagnitude - BIG_ONE, reason)) return false
        val currentQ = state.intDomains[relation.quotient]
        if (currentQ.min == currentQ.max && currentQ.min != 0L) {
            val dividend = state.intDomains[relation.dividend]
            val low = bigIntOf(dividend.min) - bigIntOf(currentR.max)
            val high = bigIntOf(dividend.max) - bigIntOf(currentR.min)
            val denominator = bigIntOf(currentQ.min)
            val lower = divideBound(if (currentQ.min > 0L) low else high, denominator, roundUp = true)
            val upper = divideBound(if (currentQ.min > 0L) high else low, denominator, roundUp = false)
            if (!tighten(state, divisor, lower, upper, reason)) return false
        }
        return true
    }

    private fun filterTuples(state: PropagationState, factorId: Int, reason: IntArray?): Boolean {
        val dividends = state.intDomains[relation.dividend].spanOrNull(MAX_PAIRS.toLong()) ?: return true
        val divisors = relation.divisor?.let { state.intDomains[it].spanOrNull(MAX_PAIRS.toLong()) ?: return true }
        if (dividends.size.toLong() * (divisors?.size ?: 1) > MAX_PAIRS) return true
        val cached = state.refPayload[factorId] as? DivModSupports
        val supports = if (cached != null && cached.covers(state)) cached else {
            val available = MAX_CACHED_CELLS - state.divModSupportCells + (cached?.storageCells ?: 0)
            DivModSupports(relation, state, available).also {
                if (it.retained) {
                    state.refPayload[factorId] = it
                    state.divModSupportCells += it.storageCells - (cached?.storageCells ?: 0)
                }
            }
        }
        return supports.filter(state, reason, MAX_PAIRS)
    }

    private fun divideBound(numerator: BigInt, denominator: BigInt, roundUp: Boolean): BigInt {
        val quotient = numerator / denominator
        val remainder = numerator - quotient * denominator
        if (remainder == BIG_ZERO) return quotient
        val positive = numerator.signum() == denominator.signum()
        return when {
            roundUp && positive -> quotient + BIG_ONE
            !roundUp && !positive -> quotient - BIG_ONE
            else -> quotient
        }
    }

    private fun tighten(state: PropagationState, variable: Int, lower: BigInt, upper: BigInt,
        reason: IntArray?): Boolean {
        val domain = state.intDomains[variable]
        if (lower > bigIntOf(domain.max) || upper < bigIntOf(domain.min)) return false
        if (lower > bigIntOf(domain.min) && !state.tightenIntMin(variable, lower.toLongExact(), reason)) return false
        return upper >= bigIntOf(domain.max) || state.tightenIntMax(variable, upper.toLongExact(), reason)
    }

    private companion object {
        const val MAX_PAIRS = 4096
        const val MAX_CACHED_CELLS = 1 shl 20
    }
}
