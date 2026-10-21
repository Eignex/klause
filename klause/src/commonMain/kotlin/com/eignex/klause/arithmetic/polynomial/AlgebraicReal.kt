package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

/** An owned exact real root of a rational polynomial, independent of arithmetic context lifetime. */
class AlgebraicReal internal constructor(
    internal val definingPolynomial: RationalPolynomial,
    /** One-based index among the defining polynomial's distinct real roots in increasing order. */
    val rootIndex: Int,
    internal val interval: RationalInterval,
) {
    /** Exact rational value when the root is represented by a point interval; otherwise null. */
    val rational: BigFraction? get() = if (interval.isPoint) interval.lower else null

    /** Captured exact rational coefficients in ascending exponent order, returned as an independent buffer. */
    val definingCoefficients: List<BigFraction> get() = definingPolynomial.coefficients

    /** Exact lower isolating bound, excluded for a nonpoint interval. */
    val lowerBound: BigFraction get() = interval.lower

    /** Exact upper isolating bound, excluded for a nonpoint interval. */
    val upperBound: BigFraction get() = interval.upper
}

internal class AlgebraicPolynomial private constructor(
    private val captured: List<AlgebraicReal>?,
    val specialization: AlgebraicSpecialization?,
) {
    constructor(coefficients: List<AlgebraicReal>) : this(coefficients.toList(), null)
    constructor(specialization: AlgebraicSpecialization) : this(null, specialization)

    val degree: Int get() = captured?.lastIndex ?: checkNotNull(specialization).degree
    val isZero: Boolean get() = degree < 0

    fun coefficients(arithmetic: AlgebraicArithmetic): List<AlgebraicReal> {
        arithmetic.arithmetic.step()
        captured?.let { return it.toList() }
        val source = checkNotNull(specialization)
        return source.coefficients.map { arithmetic.evaluate(it, source.base) }
    }
}
