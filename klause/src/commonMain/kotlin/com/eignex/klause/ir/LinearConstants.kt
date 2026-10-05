package com.eignex.klause.ir

import com.ionspin.kotlin.bignum.integer.BigInteger

/** Coefficients and right-hand side of a [LinearRow], at their declared exact width. */
sealed interface LinearConstants

/** Integral constants, including values outside the 64-bit range. */
sealed interface IntegralConstants : LinearConstants {
    /** Exact coefficient of term [k]. */
    fun exactCoeff(k: Int): BigInteger

    /** Exact right-hand side. */
    val exactBound: BigInteger
}

/** Constants representable in 64-bit integer arithmetic. */
class IntegerConstants(
    /** Coefficients in row order. */
    val coefficients: LongConstList,
    /** Right-hand side. */
    val bound: Long,
) : IntegralConstants {
    /** Coefficient of term [k]. */
    fun coeff(k: Int): Long = coefficients.at(k)

    /** Coefficients materialised for array consumers. */
    val coeffs: LongArray get() = coefficients.toLongArray()

    /** Largest absolute coefficient, saturating at [Long.MAX_VALUE]. */
    val maxAbsCoeff: Long get() = coefficients.maxAbs

    override fun exactCoeff(k: Int): BigInteger = BigInteger.fromLong(coeff(k))
    override val exactBound: BigInteger get() = BigInteger.fromLong(bound)
}

/** Integral constants requiring arbitrary precision. */
class WideConstants(
    /** Coefficients in row order. */
    val coefficients: WideConsts,
    /** Exact right-hand side. */
    val bound: BigInteger,
) : IntegralConstants {
    override fun exactCoeff(k: Int): BigInteger = coefficients.at(k)
    override val exactBound: BigInteger get() = bound

    /**
     * The decimal digits of [bound], then of each coefficient, as the character codes a structural key
     * feeds them in. Colour refinement keys a row once per incident column every round, and converting a
     * wide value to decimal costs far more than the rest of the key, so each value is converted once.
     */
    internal val keyDigits: Array<LongArray> by lazy {
        Array(coefficients.size + 1) { i -> digitCodes(if (i == 0) bound else coefficients.at(i - 1)) }
    }
}

private fun digitCodes(value: BigInteger): LongArray {
    val digits = value.toString()
    return LongArray(digits.length) { digits[it].code.toLong() }
}

/** Finite double constants, interpreted as their exact rational values. */
class RealConstants(
    /** Coefficients of discrete terms, in row order before the real terms. */
    val intCoefficients: RealConsts,
    /** Coefficients of real terms, in row order after the discrete terms. */
    val realCoefficients: RealConsts,
    /** Right-hand side. */
    val bound: Double,
    /** Whether the comparison excludes equality. */
    val strict: Boolean,
) : LinearConstants
