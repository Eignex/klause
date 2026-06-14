package com.eignex.klause.util

/**
 * Exact rational over [BigInt], kept in lowest terms with a positive denominator. Used by the LP
 * exact basis-certification fallback, where plain rational Gaussian elimination is preferred over a
 * fraction-free scheme for being obviously correct (the path is rare, so per-op gcd cost is fine).
 */
internal class BigRational private constructor(val num: BigInt, val den: BigInt) : Comparable<BigRational> {

    operator fun plus(o: BigRational): BigRational = reduced(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: BigRational): BigRational = reduced(num * o.den - o.num * den, den * o.den)
    operator fun times(o: BigRational): BigRational = reduced(num * o.num, den * o.den)
    operator fun div(o: BigRational): BigRational = reduced(num * o.den, den * o.num)

    fun signum(): Int = num.signum()

    override fun compareTo(other: BigRational): Int = (num * other.den).compareTo(other.num * den)

    /** Greatest integer `≤ this` (the denominator is positive). */
    fun floor(): BigInt {
        val (q, r) = num.divideAndRemainder(den)
        return if (r.signum() < 0) q - BigInt.ONE else q
    }

    /** Least integer `≥ this`. */
    fun ceil(): BigInt {
        val (q, r) = num.divideAndRemainder(den)
        return if (r.signum() > 0) q + BigInt.ONE else q
    }

    companion object {
        val ZERO: BigRational = BigRational(BigInt.ZERO, BigInt.ONE)

        fun of(value: Long): BigRational = of(BigInt.of(value))

        fun of(value: BigInt): BigRational = if (value.signum() == 0) ZERO else BigRational(value, BigInt.ONE)

        /** Normalize `num/den` to lowest terms with `den > 0`; `den` must be non-zero. */
        fun reduced(num: BigInt, den: BigInt): BigRational {
            check(den.signum() != 0) { "BigRational zero denominator" }
            if (num.signum() == 0) return ZERO
            var n = num
            var d = den
            if (d.signum() < 0) {
                n = -n
                d = -d
            }
            val g = n.gcd(d)
            return BigRational(n.divExact(g), d.divExact(g))
        }
    }
}
