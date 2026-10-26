package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times

internal data class LinearAuthorityAggregateTerm(
    val owner: Int,
    val premise: String,
    val inequality: SourceInequality,
    val weight: BigFraction,
)

internal data class LinearAuthorityAggregateLimits(
    val rows: Int = 4,
    val support: Int = 128,
    val scalarBits: Int = 256,
    val work: Long = 65_536L,
    val allocation: Long = 1024L * 1024L,
) {
    init {
        require(rows >= 2 && support > 0 && scalarBits in 1..4096 && work >= 0L && allocation >= 0L)
    }
}

internal object LinearAuthorityAggregateCuts {
    fun derive(
        terms: List<LinearAuthorityAggregateTerm>, cancellation: Cancellation,
        observe: (String, Long) -> Unit = { _, _ -> },
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(),
    ): LinearAuthorityCut? {
        val meter = AggregateMeter(limits, cancellation, observe)
        observe("AggregateAttempts", 1L)
        return try {
            meter.reserve(1L)
            if (terms.size !in 2..limits.rows || terms.any { it.owner < 0 || it.premise.isBlank() } ||
                terms.map { it.owner }.distinct().size != terms.size ||
                terms.map { it.premise }.distinct().size != terms.size) return null
            meter.reserve(terms.size.toLong(), terms.size * 128L)
            val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
            var rhs = BigFraction.ZERO
            var strict = false
            for (term in terms) {
                val weight = meter.number(term.weight)
                if (weight <= BigFraction.ZERO || term.inequality.supportSize > limits.support) return null
                val row = term.inequality.coefficients
                meter.reserve(row.size.toLong(), row.size * 64L)
                for ((id, value) in row) {
                    if (id.kind == SourceCoordinate.Kind.Boolean) return null
                    if (id !in coefficients && coefficients.size >= limits.support) {
                        throw AggregateStop("SupportLimit")
                    }
                    val product = meter.multiply(weight, meter.number(value))
                    coefficients[id] = meter.add(coefficients[id] ?: BigFraction.ZERO, product)
                }
                rhs = meter.add(rhs, meter.multiply(weight, meter.number(term.inequality.rhs)))
                strict = strict || term.inequality.strict
            }
            val activity = coefficients.filterValues { !it.isZero }
            if (activity.isEmpty() || activity.keys.any { it.kind != SourceCoordinate.Kind.Integer }) {
                observe("AggregateContinuousResidual", 1L)
                return null
            }
            var denominator = BIG_ONE
            for (value in activity.values) {
                meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
                denominator = denominator / denominator.gcd(value.den) * value.den
                meter.number(BigFraction.of(denominator, BIG_ONE))
            }
            var divisor = BIG_ZERO
            for (value in activity.values) {
                meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
                val integral = value.num * (denominator / value.den)
                meter.number(BigFraction.of(integral, BIG_ONE))
                meter.arithmetic(BigFraction.of(divisor, BIG_ONE), BigFraction.of(integral, BIG_ONE))
                divisor = divisor.gcd(integral.abs())
            }
            val scale = meter.number(BigFraction.of(denominator, divisor))
            val normalized = activity.mapValues { (_, value) -> meter.multiply(value, scale) }
            val scaledRhs = meter.multiply(rhs, scale)
            meter.reserve(1L + limits.scalarBits / 64L, 64L + limits.scalarBits / 4L)
            val rounded = meter.number(if (strict) {
                BigFraction.of(scaledRhs.ceilInteger(), BIG_ONE) - BigFraction.ONE
            } else BigFraction.of(scaledRhs.negated().ceilInteger(), BIG_ONE).negated())
            if (!strict && rounded == scaledRhs) {
                observe("AggregateIntegralRhs", 1L)
                return null
            }
            meter.reserve(terms.size.toLong() + normalized.size, terms.size * 128L + normalized.size * 64L)
            val proof = SourceDerivation.IntegerRounding(SourceDerivation.Combination(terms.map {
                SourceDerivation.Combination.Term(meter.multiply(it.weight, scale),
                    SourceDerivation.Premise(it.premise))
            }))
            observe("AggregateCandidates", 1L)
            LinearAuthorityCut(terms.first().owner, SourceInequality(normalized, rounded), proof,
                owners = terms.mapTo(linkedSetOf()) { it.owner })
        } catch (stop: AggregateStop) {
            observe("Aggregate${stop.reason}", 1L)
            null
        }
    }
}

private class AggregateMeter(
    private val limits: LinearAuthorityAggregateLimits,
    private val cancellation: Cancellation,
    private val observe: (String, Long) -> Unit,
) {
    private var work = 0L
    private var allocation = 0L

    fun reserve(units: Long, bytes: Long = 0L) {
        if (cancellation()) throw AggregateStop("Stopped")
        if (units > limits.work - work) throw AggregateStop("WorkLimit")
        if (bytes > limits.allocation - allocation) throw AggregateStop("AllocationLimit")
        work += units
        allocation += bytes
        observe("AggregateWork", units)
        observe("AggregateAllocation", bytes)
        cancellation.charge(units)
        if (cancellation()) throw AggregateStop("Stopped")
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        reserve(1L + bits / 64L, 32L + bits / 4L)
        if (bits > limits.scalarBits) throw AggregateStop("ScalarLimit")
        return value
    }

    fun arithmetic(left: BigFraction, right: BigFraction) {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        val words = (bits + 63L) / 64L
        reserve(1L + words * words, 64L + bits / 2L)
    }

    fun multiply(left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        return number(left * right)
    }

    fun add(left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        return number(left + right)
    }
}

private class AggregateStop(val reason: String) : RuntimeException()
