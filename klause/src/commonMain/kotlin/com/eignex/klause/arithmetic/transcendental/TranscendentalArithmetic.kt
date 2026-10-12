package com.eignex.klause.arithmetic.transcendental

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class TranscendentalInterrupted : RuntimeException()
internal class TranscendentalResourceLimit : RuntimeException()

internal class TranscendentalArithmetic(
    private val cancellation: Cancellation = Cancellation.Never,
    private val maximumBits: Int = 65536,
    private val maximumWork: Long? = null,
) {
    var work: Long = 0
        private set

    init { require(maximumBits > 0 && (maximumWork == null || maximumWork >= 0)) }

    fun step() {
        if (cancellation()) throw TranscendentalInterrupted()
        if (maximumWork != null && work >= maximumWork) throw TranscendentalResourceLimit()
        if (work == Long.MAX_VALUE) throw TranscendentalResourceLimit()
        work++
        cancellation.charge(1)
        if (cancellation()) throw TranscendentalInterrupted()
    }

    private fun bits(value: BigFraction): Long =
        maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength()).toLong()

    private fun prepare(left: BigFraction, right: BigFraction) {
        step()
        // Cross products are bounded before allocation, even when later cancellation would reduce them.
        if (bits(left) + bits(right) + 1 > maximumBits) throw TranscendentalResourceLimit()
    }

    fun add(left: BigFraction, right: BigFraction): BigFraction {
        prepare(left, right)
        return left + right
    }

    fun multiply(left: BigFraction, right: BigFraction): BigFraction {
        prepare(left, right)
        return left * right
    }

    fun divide(left: BigFraction, right: BigFraction): BigFraction {
        prepare(left, right)
        return left * right.reciprocal()
    }

    fun add(left: RationalEnclosure, right: RationalEnclosure): RationalEnclosure = RationalEnclosure(
        add(left.lower, right.lower), add(left.upper, right.upper),
    )

    fun negate(value: RationalEnclosure): RationalEnclosure {
        step()
        return RationalEnclosure(value.upper.negated(), value.lower.negated())
    }

    fun multiply(left: RationalEnclosure, right: RationalEnclosure): RationalEnclosure {
        val products = listOf(
            multiply(left.lower, right.lower), multiply(left.lower, right.upper),
            multiply(left.upper, right.lower), multiply(left.upper, right.upper),
        )
        return RationalEnclosure(products.reduce { a, b -> minimum(a, b) }, products.reduce { a, b -> maximum(a, b) })
    }

    fun reciprocal(value: RationalEnclosure): RationalEnclosure {
        step()
        require(!value.contains(BigFraction.ZERO))
        return RationalEnclosure(divide(BigFraction.ONE, value.upper), divide(BigFraction.ONE, value.lower))
    }

    fun power(value: RationalEnclosure, exponent: Int): RationalEnclosure {
        require(exponent >= 0)
        step()
        var result = RationalEnclosure.point(BigFraction.ONE)
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            if (remaining and 1 != 0) result = multiply(result, base)
            remaining = remaining ushr 1
            if (remaining != 0) base = multiply(base, base)
        }
        return result
    }

    fun exp(value: RationalEnclosure, terms: Int): RationalEnclosure = RationalEnclosure(
        expPoint(value.lower, terms).lower, expPoint(value.upper, terms).upper,
    )

    private fun expPoint(value: BigFraction, terms: Int): RationalEnclosure {
        validateTerms(terms)
        if (value.isZero) return RationalEnclosure.point(BigFraction.ONE)
        if (value.signum() < 0) return reciprocal(expPoint(value.negated(), terms))
        var reduced = value
        var squarings = 0
        val half = divide(BigFraction.ONE, q(2))
        while (reduced > half) {
            reduced = divide(reduced, q(2))
            if (squarings == Int.MAX_VALUE) throw TranscendentalResourceLimit()
            squarings++
        }
        var term = BigFraction.ONE
        var sum = term
        for (index in 1..terms) {
            term = divide(multiply(term, reduced), q(index.toLong()))
            sum = add(sum, term)
        }
        val next = divide(multiply(term, reduced), q(terms.toLong() + 1))
        val ratio = divide(reduced, q(terms.toLong() + 2))
        val tail = divide(next, add(BigFraction.ONE, ratio.negated()))
        var result = RationalEnclosure(sum, add(sum, tail))
        repeat(squarings) { result = multiply(result, result) }
        return result
    }

    fun log(value: RationalEnclosure, terms: Int): RationalEnclosure {
        validateTerms(terms)
        require(value.lower.signum() > 0) { "log enclosure requires positive arguments" }
        return RationalEnclosure(logPoint(value.lower, terms).lower, logPoint(value.upper, terms).upper)
    }

    private fun logPoint(value: BigFraction, terms: Int): RationalEnclosure {
        if (value == BigFraction.ONE) return RationalEnclosure.point(BigFraction.ZERO)
        var reduced = value
        var scale = 0L
        while (reduced < BigFraction.ONE) {
            reduced = multiply(reduced, q(2))
            if (scale == Long.MIN_VALUE) throw TranscendentalResourceLimit()
            scale--
        }
        while (reduced >= q(2)) {
            reduced = divide(reduced, q(2))
            if (scale == Long.MAX_VALUE) throw TranscendentalResourceLimit()
            scale++
        }
        val base = logReduced(reduced, terms)
        if (scale == 0L) return base
        return add(base, multiply(logReduced(q(2), terms), RationalEnclosure.point(q(scale))))
    }

    private fun logReduced(value: BigFraction, terms: Int): RationalEnclosure {
        val z = divide(add(value, BigFraction.MINUS_ONE), add(value, BigFraction.ONE))
        val square = multiply(z, z)
        var power = z
        var sum = BigFraction.ZERO
        for (index in 0 until terms) {
            sum = add(sum, divide(power, q(2L * index + 1)))
            power = multiply(power, square)
        }
        val tail = divide(multiply(q(2), power),
            multiply(q(2L * terms + 1), add(BigFraction.ONE, square.negated())))
        val lower = multiply(q(2), sum)
        return RationalEnclosure(lower, add(lower, tail))
    }

    fun pi(terms: Int): RationalEnclosure {
        validateTerms(terms)
        // atan(1/2) + atan(1/3) = pi/4: tangent addition gives 1 and both angles lie in (0, pi/4).
        return multiply(add(atanUnit(divide(BigFraction.ONE, q(2)), terms),
            atanUnit(divide(BigFraction.ONE, q(3)), terms)), RationalEnclosure.point(q(4)))
    }

    private fun atanUnit(value: BigFraction, terms: Int): RationalEnclosure {
        val square = multiply(value, value)
        var power = value
        var sum = BigFraction.ZERO
        for (index in 0 until terms) {
            val term = divide(power, q(2L * index + 1))
            sum = add(sum, if (index and 1 == 0) term else term.negated())
            power = multiply(power, square)
        }
        val next = divide(power, q(2L * terms + 1))
        return if (terms and 1 == 0) RationalEnclosure(sum, add(sum, next))
        else RationalEnclosure(add(sum, next.negated()), sum)
    }

    fun sin(value: RationalEnclosure, terms: Int): RationalEnclosure = trig(value, terms, cosine = false)
    fun cos(value: RationalEnclosure, terms: Int): RationalEnclosure = trig(value, terms, cosine = true)

    private fun trig(value: RationalEnclosure, terms: Int, cosine: Boolean): RationalEnclosure {
        validateTerms(terms)
        if (value.isPoint && value.lower.isZero) {
            return RationalEnclosure.point(if (cosine) BigFraction.ONE else BigFraction.ZERO)
        }
        val center = divide(add(value.lower, value.upper), q(2))
        val radius = divide(add(value.upper, value.lower.negated()), q(2))
        if (radius >= BigFraction.ONE) return RationalEnclosure.UNIT
        val period = multiply(pi(terms), RationalEnclosure.point(q(2)))
        val periodCenter = divide(add(period.lower, period.upper), q(2))
        val quotient = add(divide(center, periodCenter), divide(BigFraction.MINUS_ONE, q(2))).ceilInteger()
        val turns = RationalEnclosure.point(BigFraction.of(quotient, q(1).den))
        val reduced = add(RationalEnclosure.point(center), negate(multiply(period, turns)))
        val argument = divide(add(reduced.lower, reduced.upper), q(2))
        val error = add(radius, divide(add(reduced.upper, reduced.lower.negated()), q(2)))
        if (error >= BigFraction.ONE) return RationalEnclosure.UNIT
        val polynomial = trigTaylor(argument, terms, cosine)
        val lower = maximum(BigFraction.MINUS_ONE, add(polynomial.lower, error.negated()))
        val upper = minimum(BigFraction.ONE, add(polynomial.upper, error))
        return RationalEnclosure(lower, upper)
    }

    private fun trigTaylor(value: BigFraction, terms: Int, cosine: Boolean): RationalEnclosure {
        var term = BigFraction.ONE
        var sum = if (cosine) term else BigFraction.ZERO
        // The degree is bounded by validateTerms before this multiplication.
        val degree = 2 * terms
        for (index in 1..degree) {
            term = divide(multiply(term, value), q(index.toLong()))
            if ((index and 1 == 0) == cosine) {
                val negative = if (cosine) index % 4 == 2 else index % 4 == 3
                sum = add(sum, if (negative) term.negated() else term)
            }
        }
        val next = divide(multiply(term, value), q(degree.toLong() + 1))
        val error = if (next.signum() < 0) next.negated() else next
        return RationalEnclosure(add(sum, error.negated()), add(sum, error))
    }

    private fun validateTerms(terms: Int) {
        require(terms > 0)
        step()
        if (terms > (Int.MAX_VALUE - 1) / 2) throw TranscendentalResourceLimit()
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)

    private fun minimum(a: BigFraction, b: BigFraction): BigFraction = if (a <= b) a else b
    private fun maximum(a: BigFraction, b: BigFraction): BigFraction = if (a >= b) a else b
}
