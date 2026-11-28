package com.eignex.klause.util

import com.eignex.util.numbers.shl

// The Luby restart sequence 1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, … (Luby, Sinclair, Zuckerman,
// "Optimal speedup of Las Vegas algorithms", IPL 1993, https://doi.org/10.1016/0020-0190(93)90029-9),
// universally optimal up to a log factor for Las Vegas algorithms with an unknown runtime distribution. Backtrack
// search indexes lubyN; local search steps a LubyIterator. Both produce the identical sequence.

// lubyN(i) = 2^(k-1) when i = 2^k − 1, otherwise lubyN(i − 2^(k-1) + 1) where k = ⌊log₂(i)⌋ + 1, unwound
// iteratively.
internal fun lubyN(index: Long): Long {
    require(index > 0L) { "Luby index must be positive" }
    var i = index
    while (true) {
        val k = Long.SIZE_BITS - i.countLeadingZeroBits()
        val pow = 1L shl (k - 1)
        if (i == pow + (pow - 1)) return pow
        i = i - pow + 1
    }
}

// Knuth's reluctant-doubling form (TAOCP Vol. 4B §7.2.2.2): O(1) per step, starting at the first term.
internal class LubyIterator {
    private var u = 1
    private var v = 1

    val value: Int get() = v

    fun advance() {
        if ((u and -u) == v) {
            u += 1
            v = 1
        } else {
            v *= 2
        }
    }

    fun reset() {
        u = 1
        v = 1
    }
}
