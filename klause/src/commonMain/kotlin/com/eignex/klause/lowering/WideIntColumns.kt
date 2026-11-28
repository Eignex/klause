package com.eignex.klause.lowering

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.toLongExact

// Signed digit encoding `Σ d(i)·2^(width·i)`, least significant first. Digits range over `[0, 2^width)`
// except the leading one, over `[−2^width, 2^width)`, so each in-range value has exactly one representation.
internal class WideIntColumns(val columns: IntArray, val width: Int) {
    fun weights(): Array<BigInt> = Array(columns.size) { WideIntDigits.pow2(width * it) }
}

// The width keeps `maxCoeff·2^width` inside `Long` so ordinary bound propagation stays usable; null when no
// width fits, leaving the row to wide-coefficient propagation.
internal fun wideIntColumns(magnitude: BigInt, maxCoeff: BigInt, fresh: (Long, Long) -> Int): WideIntColumns? {
    val width = WideIntDigits.widthFor(maxCoeff)
    if (width == WideIntDigits.NO_ROOM) return null
    // One extra position gives the signed leading digit room for the sign.
    val count = WideIntDigits.digitCount(magnitude, width) + 1
    val max = (WideIntDigits.pow2(width) - BIG_ONE).toLongExact()
    val cols = IntArray(count) { i -> if (i == count - 1) fresh(-max - 1, max) else fresh(0L, max) }
    return WideIntColumns(cols, width)
}
