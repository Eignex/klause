package com.eignex.klause.ir

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt

/** Normalized rational source-semantic metadata, independent of execution arithmetic types. */
class SemanticRational private constructor(
    private val value: BigFraction,
) : Comparable<SemanticRational> {
    /** Reduced signed numerator. */
    val numerator: BigInt get() = value.num

    /** Reduced positive denominator. */
    val denominator: BigInt get() = value.den

    override fun compareTo(other: SemanticRational): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is SemanticRational &&
        value == other.value

    override fun hashCode(): Int = value.hashCode()

    /** Exact semantic-value construction. */
    companion object {
        /** Additive identity. */
        val ZERO: SemanticRational = SemanticRational(BigFraction.ZERO)

        /** Multiplicative identity. */
        val ONE: SemanticRational = SemanticRational(BigFraction.ONE)

        /** Capture an exact integer value. */
        fun ofLong(value: Long): SemanticRational = SemanticRational(BigFraction.ofLong(value))

        /** Capture an exact rational, normalizing sign and common factors. */
        fun of(numerator: BigInt, denominator: BigInt = BIG_ONE): SemanticRational =
            SemanticRational(BigFraction.of(numerator, denominator))
    }
}
