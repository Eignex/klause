package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

internal class PrivateRebuildAffine(
    private val fixed: Array<Boolean?>,
    private val coordinates: IntArray,
    private val pivots: IntArray,
    private val rhs: IntArray,
    private val dependencies: Array<LongArray>,
    val rank: Int,
    val freeColumns: Int,
) {
    private val words = (freeColumns + 63) ushr 6
    val reconstructionWork: Long = coordinates.size * 24L + rank * (words * 8L + 8L)

    fun privateValue(column: Int): Boolean? = when {
        coordinates[column] == Int.MIN_VALUE -> fixed[column]
        coordinates[column] >= 0 -> false
        else -> null
    }

    fun reconstruct(sample: Sample): Sample {
        val free = LongArray(words)
        val point = BooleanArray(coordinates.size)
        for (column in coordinates.indices) {
            val coordinate = coordinates[column]
            if (coordinate == Int.MIN_VALUE) point[column] = checkNotNull(fixed[column])
            else if (coordinate < 0) {
                val index = -coordinate - 1
                val value = sample.boolValue(column)
                point[column] = value
                if (value) free[index ushr 6] = free[index ushr 6] or (1L shl (index and 63))
            }
        }
        for (row in 0 until rank) {
            var value = rhs[row]
            for (word in 0 until words) {
                val parity = (dependencies[row][word] and free[word]).countOneBits() and 1
                value = value xor parity
            }
            point[pivots[row]] = value != 0
        }
        return Sample(point, longArrayOf())
    }

    fun rowLiteralCount(cancellation: Cancellation, onDecline: (String) -> Unit): Long? {
        var result = 0L
        for (row in 0 until rank) {
            cancellation.charge(words * 2L + 4L)
            if (cancellation()) return null
            val width = 1 + dependencies[row].sumOf { it.countOneBits() }
            if (width > 64) { onDecline("ParityRowWidth"); return null }
            result += width
        }
        return result
    }

    fun materializeRows(cancellation: Cancellation): Array<Factor>? {
        cancellation.charge(coordinates.size * 2L + rank + 8L)
        if (cancellation()) return null
        val free = IntArray(freeColumns)
        for (column in coordinates.indices) {
            val coordinate = coordinates[column]
            if (coordinate < 0 && coordinate != Int.MIN_VALUE) free[-coordinate - 1] = column
        }
        val result = ArrayList<Factor>(rank)
        for (row in 0 until rank) {
            cancellation.charge(words * 2L + 4L)
            if (cancellation()) return null
            val width = 1 + dependencies[row].sumOf { it.countOneBits() }
            if (width > 64) return null
            // The factor builds its distinct-coordinate set; the projection retains unit coefficients.
            cancellation.charge(width * width.toLong() + width * 16L + 16L)
            if (cancellation()) return null
            val literals = IntArray(width)
            literals[0] = Lit.make(pivots[row], true)
            var next = 1
            for (word in 0 until words) {
                var bits = dependencies[row][word]
                while (bits != 0L) {
                    val index = word * 64 + bits.countTrailingZeroBits()
                    literals[next++] = Lit.make(free[index], true)
                    bits = bits and (bits - 1)
                }
            }
            result.add(Xor(literals, rhs[row]))
        }
        return result.toTypedArray()
    }
}
