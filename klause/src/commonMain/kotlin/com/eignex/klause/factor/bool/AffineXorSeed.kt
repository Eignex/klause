package com.eignex.klause.factor.bool

internal class AffineXorSeed(
    val coordinates: IntArray,
    val rhs: IntArray,
    val dependencies: Array<LongArray>,
    val freeColumns: Int,
    val variables: IntArray = IntArray(coordinates.size) { it },
) {
    val words: Int = ((freeColumns.toLong() + 63L) / 64L).toInt()
    val reasonWords: Int = ((freeColumns.toLong() + 64L) / 64L).toInt()
    val initializationWork: Long = initializationWork(coordinates.size, freeColumns)

    companion object {
        fun initializationWork(columns: Int, free: Int): Long {
            val words = (free.toLong() + 63L) / 64L
            val reasons = (free.toLong() + 64L) / 64L
            return (free * (words + reasons) + words * 2L + reasons + columns + free * 3L + 1L) * 2L + 32L
        }

        fun modeledBytes(columns: Int, rank: Int, free: Int): Long {
            val words = (free.toLong() + 63L) / 64L
            val reasons = (free.toLong() + 64L) / 64L
            val longSlots = free * (words + reasons) + words * 2L + reasons
            val intSlots = columns + free * 3L + 1L
            val snapshot = columns * 9L + rank * (words * 8L + 12L)
            return snapshot + longSlots * 8L + intSlots * 4L + columns * 1152L + rank * 8L + 256L
        }
    }
}
