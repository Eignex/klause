package com.eignex.klause.lowering

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

// `x = Σ d(i)·2^(width·i)` over ordinary `Long` digits in `[0, 2^width)`.
internal object WideIntDigits {

    // The widest digit whose bound `2^width` still fits a signed `Long`.
    private const val MAX_WIDTH = 62

    private const val MIN_WIDTH = 1

    const val NO_ROOM = -1

    // The largest `k` with `maxCoeff·2^k < 2^63`, so no coefficient-times-digit product overflows. A
    // coefficient that overflows even with a one-bit digit yields NO_ROOM: decomposition cannot help.
    fun widthFor(maxCoeff: BigInt): Int {
        val c = maxCoeff.abs()
        if (c <= BIG_ONE) return MAX_WIDTH
        val limit = bigIntOf(Long.MAX_VALUE)
        var k = MAX_WIDTH
        while (k >= MIN_WIDTH && c * pow2(k) > limit) k--
        return if (k >= MIN_WIDTH) k else NO_ROOM
    }

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

    fun digitsOf(value: BigInt, width: Int, count: Int): LongArray {
        require(value.signum() >= 0) { "digitsOf takes a non-negative value" }
        val radix = pow2(width)
        var rest = value
        return LongArray(count) {
            val d = rest.remainderOf(radix)
            rest /= radix
            d.toLongExact()
        }
    }

    fun recompose(digits: LongArray, width: Int): BigInt {
        var acc = BIG_ZERO
        for (i in digits.indices.reversed()) acc = acc * pow2(width) + bigIntOf(digits[i])
        return acc
    }

    fun pow2(k: Int): BigInt = BIG_ONE.shl(k)
}
