package com.eignex.klause.arithmetic.polynomial

import com.eignex.util.numbers.BigFraction

/** An owned exact real root of a rational polynomial, independent of arithmetic context lifetime. */
class AlgebraicReal internal constructor(
    internal val definingPolynomial: RationalPolynomial,
    /** One-based index among the defining polynomial's distinct real roots in increasing order. */
    val rootIndex: Int,
    internal val interval: RationalInterval,
    internal val image: AlgebraicImage? = null,
) {
    /** Exact rational value when the root is represented by a point interval; otherwise null. */
    val rational: BigFraction? get() = if (interval.isPoint) interval.lower else null

    /** Captured exact rational coefficients in ascending exponent order, returned as an independent buffer. */
    val definingCoefficients: List<BigFraction> get() = definingPolynomial.coefficients

    /** Exact lower isolating bound, excluded for a nonpoint interval. */
    val lowerBound: BigFraction get() = interval.lower

    /** Exact upper isolating bound, excluded for a nonpoint interval. */
    val upperBound: BigFraction get() = interval.upper

    // How the isolating interval stands against binary64 rounding; see [roundToBinary64].
    internal fun binaryRounding(): BinaryRounding = roundToBinary64(interval)

    // The binary64 value nearest this root, or null while the isolating interval spans more than one rounding cell.
    internal val nearestDouble: Double? get() = (binaryRounding() as? BinaryRounding.Determined)?.value

    // A cheap guide for search: the rational's conversion, or the mean of the converted interval endpoints.
    internal val guideDouble: Double
        get() = rational?.toDouble() ?: (interval.lower.toDouble() / 2 + interval.upper.toDouble() / 2)

    // [nearestDouble] when the interval settles it, otherwise the rounding of the interval's midpoint.
    internal val approximateDouble: Double get() = when (val rounding = binaryRounding()) {
        is BinaryRounding.Determined -> rounding.value
        is BinaryRounding.Split -> rounding.approximate
    }
}

internal class AlgebraicImage(val base: AlgebraicReal, val polynomial: RationalPolynomial)

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
