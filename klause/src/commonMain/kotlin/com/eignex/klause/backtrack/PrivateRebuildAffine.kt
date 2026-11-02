package com.eignex.klause.backtrack

import com.eignex.klause.solver.Sample

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
    val reconstructionWork: Long = coordinates.size * 12L + rank * (words * 4L + 4L)

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
}
