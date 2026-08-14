package com.eignex.klause.formats

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A wide integer quantity rewritten as digit columns.
 *
 * [columns] are the variables standing for the digits, least significant first, and the value is
 * `Σᵢ dᵢ·2^(width·i)`. Every digit but the last ranges over `[0, 2^width)`; the most significant one
 * ranges over `[−2^width, 2^width)` and carries the sign.
 *
 * The signed leading digit is what makes the encoding a *bijection*: every value in range has exactly
 * one digit vector, because the leading digit is the quotient by `2^(width·(n−1))` and the rest is its
 * remainder. Carrying the sign instead as the difference of two non-negative vectors would give a single
 * value as many representations as a digit has values, and search would wander through all of them
 * before deciding anything.
 */
internal class WideIntColumns(val columns: IntArray, val width: Int) {
    /** `2^(width·i)` per digit position, the coefficient a rewritten row multiplies each digit by. */
    fun weights(): Array<BigInteger> = Array(columns.size) { WideIntDigits.pow2(width * it) }
}

/**
 * Allocate digit columns for a quantity reaching [magnitude], appearing with coefficients up to
 * [maxCoeff]. [fresh] supplies a new variable index per call, receiving the digit's inclusive bounds so
 * the caller can give the column its domain.
 *
 * Returns null when [maxCoeff] leaves no usable digit width ([WideIntDigits.NO_ROOM]): the coefficient
 * itself is out of range there, so the row belongs to the wide-coefficient propagators rather than to
 * this decomposition.
 *
 * The width is chosen so that `maxCoeff · 2^width` stays inside `Long`, which keeps the ordinary bound
 * reasoning working at the least significant position — decomposing alone is not enough, because a
 * propagator that overflows multiplying a coefficient by a domain bound stops deriving the bound and the
 * row silently loses its refutation strength while still admitting solutions.
 */
internal fun wideIntColumns(
    magnitude: BigInteger,
    maxCoeff: BigInteger,
    fresh: (Long, Long) -> Int,
): WideIntColumns? {
    val width = WideIntDigits.widthFor(maxCoeff)
    if (width == WideIntDigits.NO_ROOM) return null
    // One position beyond the magnitude's own digits, so the leading signed digit has room for the sign
    // without borrowing range from the value.
    val count = WideIntDigits.digitCount(magnitude, width) + 1
    val max = (WideIntDigits.pow2(width) - BigInteger.ONE).longValue()
    val cols = IntArray(count) { i -> if (i == count - 1) fresh(-max - 1, max) else fresh(0L, max) }
    return WideIntColumns(cols, width)
}
