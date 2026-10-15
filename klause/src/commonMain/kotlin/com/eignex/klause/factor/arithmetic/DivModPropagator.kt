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
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
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
        return filterTuples(state, constant, reason)
    }

    private fun filterTuples(state: PropagationState, constant: BigInt?, reason: IntArray?): Boolean {
        val dividends = state.intDomains[relation.dividend].spanOrNull(MAX_PAIRS.toLong()) ?: return true
        val divisors = relation.divisor?.let { state.intDomains[it].spanOrNull(MAX_PAIRS.toLong()) ?: return true }
        if (dividends.size.toLong() * (divisors?.size ?: 1) > MAX_PAIRS) return true
        val variables = relation.intVars.distinct()
        val supports = variables.associateWith { HashSet<Long>() }
        for (i in 0 until dividends.size) {
            val av = dividends.valueAt(i)
            for (j in 0 until (divisors?.size ?: 1)) {
                val bv = divisors?.valueAt(j)
                val outputs = relation.outputs(bigIntOf(av), bv?.let(::bigIntOf) ?: checkNotNull(constant)) ?: continue
                if (!outputs.first.fitsLong() || !outputs.second.fitsLong()) continue
                val tuple = linkedMapOf(relation.dividend to av)
                fun put(v: Int, value: Long): Boolean {
                    if (value !in state.intDomains[v]) return false
                    val previous = tuple.put(v, value)
                    return previous == null || previous == value
                }
                if (relation.divisor != null && !put(relation.divisor, checkNotNull(bv))) continue
                if (!put(relation.quotient, outputs.first.toLongExact()) ||
                    !put(relation.remainder, outputs.second.toLongExact())) continue
                for ((v, value) in tuple) supports.getValue(v).add(value)
            }
        }
        for (v in variables) {
            val values = supports.getValue(v)
            if (values.isEmpty()) return false
            if (!state.tightenIntMin(v, values.min(), reason) || !state.tightenIntMax(v, values.max(), reason)) return false
            val domain = state.intDomains[v].spanOrNull(MAX_PAIRS.toLong()) ?: continue
            for (i in 0 until domain.size) {
                val value = domain.valueAt(i)
                if (value !in values && !state.excludeIntValue(v, value, reason)) return false
            }
        }
        return true
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
    }
}
