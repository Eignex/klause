package com.eignex.klause.ir

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

/** Normalized rational source-semantic metadata, independent of execution arithmetic types. */
class SemanticRational private constructor(
    /** Reduced signed numerator. */
    val numerator: BigInt,
    /** Reduced positive denominator. */
    val denominator: BigInt,
) : Comparable<SemanticRational> {
    override fun compareTo(other: SemanticRational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun equals(other: Any?): Boolean = other is SemanticRational &&
        numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    /** Exact semantic-value construction. */
    companion object {
        /** Additive identity. */
        val ZERO: SemanticRational = ofLong(0)

        /** Multiplicative identity. */
        val ONE: SemanticRational = ofLong(1)

        /** Capture an exact integer value. */
        fun ofLong(value: Long): SemanticRational = of(bigIntOf(value))

        /** Capture an exact rational, normalizing sign and common factors. */
        fun of(numerator: BigInt, denominator: BigInt = BIG_ONE): SemanticRational {
            require(!denominator.isZero()) { "zero semantic rational denominator" }
            val divisor = numerator.gcd(denominator)
            val sign = if (denominator.signum() < 0) -divisor else divisor
            return SemanticRational(numerator / sign, denominator / sign)
        }
    }
}
