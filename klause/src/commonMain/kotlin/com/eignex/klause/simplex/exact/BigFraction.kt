package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.shl
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toDouble
import com.eignex.klause.util.unaryMinus

/** Immutable rational number over the multiplatform big integer, normalized with a positive denominator. */
class BigFraction private constructor(
    /** The reduced signed numerator. */
    val num: BigInt,
    /** The reduced positive denominator. */
    val den: BigInt,
) {

    /** Whether this fraction is zero. */
    val isZero: Boolean get() = num.isZero()

    /** The sign of this fraction: `-1`, `0`, or `1`. */
    fun signum(): Int = num.signum()

    /** Returns the additive inverse of this fraction. */
    fun negated(): BigFraction = if (isZero) this else BigFraction(-num, den)

    internal fun ceilInteger(): BigInt {
        if (den.isOne) return num
        val quotient = num / den
        return if (num.signum() > 0 && !(num % den).isZero()) quotient + BIG_ONE else quotient
    }

    /** Returns this fraction converted to a [Double]. */
    fun toDouble(): Double = num.toDouble() / den.toDouble()

    /** Returns the sum of this fraction and [other]. */
    operator fun plus(other: BigFraction): BigFraction = sum(other.num, other.den)

    /** Returns this fraction minus [other]. */
    operator fun minus(other: BigFraction): BigFraction = sum(-other.num, other.den)

    // `num/den + n/d` for a reduced `n/d`, normalized by Knuth's method (TAOCP 4.5.1): the gcds run on the
    // denominators and their quotient instead of on the full cross products, and vanish when a denominator is 1
    // or the denominators are coprime, since a reduced sum over coprime denominators is already reduced.
    private fun sum(n: BigInt, d: BigInt): BigFraction {
        if (n.isZero()) return this
        if (isZero) return BigFraction(n, d)
        if (d.isOne) return BigFraction(num + n * den, den)
        if (den.isOne) return BigFraction(num * d + n, d)
        val g = den.gcd(d)
        if (g.isOne) return BigFraction(num * d + n * den, den * d)
        val dg = d / g
        val t = num * dg + n * (den / g)
        if (t.isZero()) return ZERO
        val h = t.gcd(g)
        return if (h.isOne) BigFraction(t, den * dg) else BigFraction(t / h, den / h * dg)
    }

    /** Returns the product of this fraction and [other], reducing each numerator against the other denominator. */
    operator fun times(other: BigFraction): BigFraction {
        if (isZero || other.isZero) return ZERO
        if (den.isOne && num.isUnit) return if (num.isOne) other else other.negated()
        if (other.den.isOne && other.num.isUnit) return if (other.num.isOne) this else negated()
        val g1 = if (den.isOne || other.num.isUnit) BIG_ONE else other.num.gcd(den)
        val g2 = if (other.den.isOne || num.isUnit) BIG_ONE else num.gcd(other.den)
        val n1 = if (g2.isOne) num else num / g2
        val d2 = if (g2.isOne) other.den else other.den / g2
        val n2 = if (g1.isOne) other.num else other.num / g1
        val d1 = if (g1.isOne) den else den / g1
        return BigFraction(n1 * n2, d1 * d2)
    }

    /** Returns the multiplicative inverse of this non-zero fraction. */
    fun reciprocal(): BigFraction {
        require(!isZero) { "reciprocal of zero" }
        return if (num.signum() < 0) BigFraction(-den, -num) else BigFraction(den, num)
    }

    /** Compares this fraction with [other]. */
    operator fun compareTo(other: BigFraction): Int {
        val sign = num.signum()
        val otherSign = other.num.signum()
        if (sign != otherSign || sign == 0) return sign.compareTo(otherSign)
        if (den === other.den || den.compareTo(other.den) == 0) return num.compareTo(other.num)
        return (num * other.den).compareTo(other.num * den)
    }

    override fun equals(other: Any?): Boolean = this === other || (
        other is BigFraction &&
            (num === other.num || num.compareTo(other.num) == 0) &&
            (den === other.den || den.compareTo(other.den) == 0)
        )

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    override fun toString(): String = if (den == BIG_ONE) "$num" else "$num/$den"

    /** Factories and constants for exact rational values. */
    companion object {
        /** The additive identity. */
        val ZERO = BigFraction(BIG_ZERO, BIG_ONE)

        /** The multiplicative identity. */
        val ONE = BigFraction(BIG_ONE, BIG_ONE)

        /** The additive inverse of [ONE]. */
        val MINUS_ONE = BigFraction(-BIG_ONE, BIG_ONE)

        /** Returns the integer fraction represented by [v]. */
        fun ofLong(v: Long): BigFraction = if (v == 0L) ZERO else BigFraction(bigIntOf(v), BIG_ONE)

        /** Returns the normalized fraction [num] / [den]. */
        fun of(num: BigInt, den: BigInt): BigFraction {
            require(!den.isZero()) { "zero denominator" }
            if (num.isZero()) return ZERO
            val negative = den.signum() < 0
            val n = if (negative) -num else num
            val d = if (negative) -den else den
            if (d.isOne || n.isUnit) return BigFraction(n, d)
            val g = n.gcd(d)
            return if (g.isOne) BigFraction(n, d) else BigFraction(n / g, d / g)
        }

        /** The exact rational value of a finite double: `v = ±m·2ᵉ` from its IEEE decomposition.
         *  Null for non-finite values. */
        fun ofDouble(v: Double): BigFraction? {
            if (v == 0.0) return ZERO
            if (!v.isFinite()) return null
            val bits = v.toRawBits()
            val expBits = ((bits ushr 52) and 0x7FFL).toInt()
            var m = bits and 0xFFFFFFFFFFFFFL
            var e = if (expBits == 0) {
                -1074
            } else {
                m = m or (1L shl 52)
                expBits - 1075
            }
            val tz = m.countTrailingZeroBits()
            m = m shr tz
            e += tz
            val mag = bigIntOf(if (bits < 0L) -m else m)
            // The odd significand is coprime to the power-of-two denominator.
            return if (e >= 0) BigFraction(mag shl e, BIG_ONE) else BigFraction(mag, BIG_ONE shl -e)
        }
    }
}

// Native BigInt equality goes through a generic comparison, which costs more than the arithmetic it would skip.
private val BigInt.isUnit: Boolean get() = magnitudeBitLength() == 1

private val BigInt.isOne: Boolean get() = signum() > 0 && magnitudeBitLength() == 1
