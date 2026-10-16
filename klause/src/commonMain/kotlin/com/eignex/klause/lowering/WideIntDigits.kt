package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.shl
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * Encode nonnegative integers as ordinary `Long` variables:
 * `x = Σᵢ dᵢ·2^(width·i)`, with each `dᵢ` in `[0, 2^width)`.
 *
 * [widthFor] chooses width against the largest row coefficient, keeping coefficient-times-digit
 * bounds inside `Long` so ordinary propagation can derive bounds without overflow.
 */
internal object WideIntDigits {

    /** Widest digit, whose bound `2^width` still fits a signed `Long`. */
    private const val MAX_WIDTH = 62

    /** Narrowest digit; a coefficient that overflows even this yields [NO_ROOM]. */
    private const val MIN_WIDTH = 1

    /** Returned by [widthFor] when no digit width keeps `coeff × 2^width` inside `Long`; such a row stays
     *  a wide-coefficient row, since the coefficient itself is out of range. */
    const val NO_ROOM = -1

    /**
     * The digit width to use for a variable appearing with coefficients up to [maxCoeff]: the largest
     * `k` with `maxCoeff·2^k < 2^63`, so no `coefficient × digit-bound` product overflows.
     *
     * A coefficient so large that even a one-bit digit overflows yields [NO_ROOM]: decomposing the
     * variable cannot help there, because the coefficient itself is what leaves range.
     */
    fun widthFor(maxCoeff: BigInt): Int {
        val c = maxCoeff.abs()
        if (c <= BIG_ONE) return MAX_WIDTH
        val limit = bigIntOf(Long.MAX_VALUE)
        var k = MAX_WIDTH
        while (k >= MIN_WIDTH && c * pow2(k) > limit) k--
        return if (k >= MIN_WIDTH) k else NO_ROOM
    }

    /** Number of unsigned [width]-bit digits needed to represent `|magnitude|`; [wideIntColumns] adds the
     *  signed leading digit. */
    fun digitCount(magnitude: BigInt, width: Int): Int {
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
     * Negative values are not encoded here; [WideIntColumns] carries the sign in its leading digit.
     */
    fun digitsOf(value: BigInt, width: Int, count: Int): LongArray {
        require(value.signum() >= 0) { "digitsOf takes a non-negative value" }
        val radix = pow2(width)
        var rest = value
        return LongArray(count) {
            val d = rest % radix
            rest /= radix
            d.toLongExact()
        }
    }

    /** The value [digits] encode at [width] — the inverse of [digitsOf]. */
    fun recompose(digits: LongArray, width: Int): BigInt {
        var acc = BIG_ZERO
        for (i in digits.indices.reversed()) acc = acc * pow2(width) + bigIntOf(digits[i])
        return acc
    }

    /** `2^k`. */
    fun pow2(k: Int): BigInt = BIG_ONE.shl(k)
}
