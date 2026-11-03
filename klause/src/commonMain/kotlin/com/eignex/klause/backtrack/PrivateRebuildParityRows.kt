package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation

internal class PrivateRebuildParityRows(
    private val masks: Array<LongArray>,
    private val rhs: IntArray,
) {
    fun rowLiteralCount(cancellation: Cancellation): Long? {
        var result = 0L
        for (row in rhs.indices) {
            cancellation.charge(masks[row].size * 2L + 4L)
            if (cancellation()) return null
            val width = masks[row].sumOf { it.countOneBits() }
            if (width > 64) return null
            result += width
        }
        return result
    }

    fun materializeRows(cancellation: Cancellation): Array<Factor>? {
        cancellation.charge(rhs.size + 8L)
        if (cancellation()) return null
        val result = ArrayList<Factor>(rhs.size)
        for (row in rhs.indices) {
            cancellation.charge(masks[row].size * 2L + 4L)
            if (cancellation()) return null
            val width = masks[row].sumOf { it.countOneBits() }
            if (width > 64) return null
            cancellation.charge(width * width.toLong() + width * 16L + 16L)
            if (cancellation()) return null
            val literals = IntArray(width)
            var next = 0
            for (word in masks[row].indices) {
                var bits = masks[row][word]
                while (bits != 0L) {
                    literals[next++] = Lit.make(word * 64 + bits.countTrailingZeroBits(), true)
                    bits = bits and (bits - 1)
                }
            }
            result.add(Xor(literals, rhs[row]))
        }
        return result.toTypedArray()
    }
}
