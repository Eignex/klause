package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.minus
import com.eignex.klause.util.toLongExact

/**
 * Signed digit encoding `Σᵢ dᵢ·2^(width·i)`. [columns] are least significant first. All digits
 * range over `[0, 2^width)` except the leading digit, which ranges over `[−2^width, 2^width)`.
 * The leading quotient and lower remainder give each in-range value exactly one representation.
 */
internal class WideIntColumns(val columns: IntArray, val width: Int) {
    /** `2^(width·i)` per digit position, the coefficient a rewritten row multiplies each digit by. */
    fun weights(): Array<BigInt> = Array(columns.size) { WideIntDigits.pow2(width * it) }
}

/**
 * Allocate digit columns for [magnitude] with row coefficients up to [maxCoeff]. [fresh]
 * receives each digit's inclusive bounds. Width keeps `maxCoeff · 2^width` inside `Long` so
 * ordinary bound propagation remains usable. Returns null ([WideIntDigits.NO_ROOM]) when
 * no digit width fits; such coefficients require wide-coefficient propagation.
 */
internal fun wideIntColumns(magnitude: BigInt, maxCoeff: BigInt, fresh: (Long, Long) -> Int): WideIntColumns? {
    val width = WideIntDigits.widthFor(maxCoeff)
    if (width == WideIntDigits.NO_ROOM) return null
    // One position beyond the magnitude's own digits, so the leading signed digit has room for the sign
    // without borrowing range from the value.
    val count = WideIntDigits.digitCount(magnitude, width) + 1
    val max = (WideIntDigits.pow2(width) - BIG_ONE).toLongExact()
    val cols = IntArray(count) { i -> if (i == count - 1) fresh(-max - 1, max) else fresh(0L, max) }
    return WideIntColumns(cols, width)
}
