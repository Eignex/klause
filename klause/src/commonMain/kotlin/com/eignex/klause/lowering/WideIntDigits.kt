package com.eignex.klause.lowering

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Positional encoding of an integer too large for a [Long] domain, as digits over ordinary `Long`
 * variables: `x = Σᵢ dᵢ·2^(width·i)`, each `dᵢ` in `[0, 2^width)`.
 *
 * This is how arbitrary magnitude reaches the solver without the kernel changing: the digits are
 * ordinary integer variables, so domains, the trail, propagation, the order atoms and the LP relaxation
 * all stay exactly as they are, and the precision lives in the encoding.
 *
 * [widthFor] is the load-bearing part. Decomposing the *variables* is not enough on its own — a row's
 * bound reasoning multiplies a coefficient by a domain bound, and once that product leaves `Long` the
 * ordinary propagator stops deriving the bound, so satisfying assignments are still findable but
 * refutations are lost. Choosing the width against the largest coefficient in the rows a variable
 * appears in keeps every such product in range, which is what keeps refutation working.
 */
internal object WideIntDigits {

    /** Largest exponent whose products stay inside `Long`; below this the encoding would not help. */
    private const val MAX_WIDTH = 62

    /** Narrowest useful digit. A coefficient so large that even this overflows leaves no room at all —
     *  [widthFor] reports that with [NO_ROOM] rather than returning a width whose products still wrap. */
    private const val MIN_WIDTH = 1

    /** Returned by [widthFor] when no digit width keeps `coeff × 2^width` inside `Long`. Such a row must
     *  be handled as a WIDE COEFFICIENT (which the wide linear propagators already do) rather than by
     *  decomposing the variable, since the coefficient itself is the thing out of range. */
    const val NO_ROOM = -1

    /**
     * The digit width to use for a variable appearing with coefficients up to [maxCoeff]: the largest
     * `k` with `maxCoeff·2^k < 2^63`, so no `coefficient × digit-bound` product overflows.
     *
     * A coefficient so large that even a one-bit digit overflows yields [NO_ROOM]: decomposing the
     * variable cannot help there, because the coefficient itself is what leaves range.
     */
    fun widthFor(maxCoeff: BigInteger): Int {
        val c = maxCoeff.abs()
        if (c <= BigInteger.ONE) return MAX_WIDTH
        val limit = BigInteger.fromLong(Long.MAX_VALUE)
        var k = MAX_WIDTH
        while (k >= MIN_WIDTH && c * pow2(k) > limit) k--
        return if (k >= MIN_WIDTH) k else NO_ROOM
    }

    /** Number of [width]-bit digits needed to represent every value in `[-magnitude, magnitude]`. */
    fun digitCount(magnitude: BigInteger, width: Int): Int {
        val m = magnitude.abs()
        var n = 1
        var covered = pow2(width)
        while (covered <= m) {
            covered *= pow2(width)
            n++
        }
        return n
    }

    /**
     * The digits of a non-negative [value] at [width], least significant first, padded to [count].
     *
     * Negative values are not encoded here: a signed quantity is carried as the difference of two
     * non-negative digit vectors, which keeps every digit's domain a plain `[0, 2^width)` and leaves the
     * sign to the linear row rather than to the encoding.
     */
    fun digitsOf(value: BigInteger, width: Int, count: Int): LongArray {
        require(value.signum() >= 0) { "digitsOf takes a non-negative value" }
        val radix = pow2(width)
        var rest = value
        return LongArray(count) {
            val d = rest % radix
            rest /= radix
            d.longValue()
        }
    }

    /** The value [digits] encode at [width] — the inverse of [digitsOf]. */
    fun recompose(digits: LongArray, width: Int): BigInteger {
        var acc = BigInteger.ZERO
        for (i in digits.indices.reversed()) acc = acc * pow2(width) + BigInteger.fromLong(digits[i])
        return acc
    }

    /** `2^k`, which the bignum library expresses as a shift of one. */
    fun pow2(k: Int): BigInteger = BigInteger.ONE.shl(k)
}
