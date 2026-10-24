package com.eignex.klause.factor.table.internals

import com.eignex.klause.util.argsortBy

internal class TableSupportIndex private constructor(val columns: Array<Column>, val retainedBytes: Long) {
    internal class Column(
        private val values: LongArray,
        private val starts: IntArray,
        private val rows: IntArray,
        private val masks: LongArray,
        private val words: Int,
    ) {
        fun forEachRow(lower: Long, upper: Long, visit: (Int) -> Unit) {
            var bucket = lowerBound(lower)
            while (bucket < values.size && values[bucket] <= upper) {
                val start = starts[bucket]
                if (start >= 0) {
                    var end = bucket + 1
                    while (end < starts.size && starts[end] < 0) end++
                    val limit = if (end < starts.size) starts[end] else rows.size
                    for (i in start until limit) visit(rows[i])
                } else {
                    val base = -start - 1
                    for (i in 0 until words) {
                        var bits = masks[base + i]
                        while (bits != 0L) {
                            visit((i shl 6) + bits.countTrailingZeroBits())
                            bits = bits and (bits - 1)
                        }
                    }
                }
                bucket++
            }
        }

        private fun lowerBound(value: Long): Int {
            var lo = 0
            var hi = values.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (values[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    companion object {
        private const val MAX_BYTES = 32L * 1024 * 1024

        fun build(tuples: LongArray, arity: Int, maxBytes: Long = MAX_BYTES): TableSupportIndex? {
            val count = tuples.size / arity
            // Sorting needs two primitive row permutations; decline before allocating either.
            if (count.toLong() * 8 > maxBytes) return null
            val columns = ArrayList<Column>(arity)
            var retained = 0L
            val words = ((count.toLong() + 63) ushr 6).toInt()
            for (col in 0 until arity) {
                if (retained + count.toLong() * 8 > maxBytes) return null
                val order = argsortBy(count) { a, b -> tuples[a * arity + col].compareTo(tuples[b * arity + col]) }
                var buckets = 0
                var sparse = 0
                var dense = 0
                var i = 0
                while (i < count) {
                    var end = i + 1
                    while (end < count && tuples[order[end] * arity + col] == tuples[order[i] * arity + col]) end++
                    buckets++
                    if (words.toLong() * 8 < (end - i).toLong() * 4) dense += words else sparse += end - i
                    i = end
                }
                val bytes = buckets.toLong() * 12 + sparse.toLong() * 4 + dense.toLong() * 8
                if (retained + bytes + count.toLong() * 8 > maxBytes) return null
                val values = LongArray(buckets)
                val starts = IntArray(buckets)
                val rows = IntArray(sparse)
                val masks = LongArray(dense)
                var bucket = 0
                var rowSlot = 0
                var maskSlot = 0
                i = 0
                while (i < count) {
                    val value = tuples[order[i] * arity + col]
                    var end = i + 1
                    while (end < count && tuples[order[end] * arity + col] == value) end++
                    values[bucket] = value
                    if (words.toLong() * 8 < (end - i).toLong() * 4) {
                        starts[bucket] = -maskSlot - 1
                        for (k in i until end) {
                            val row = order[k]
                            val slot = maskSlot + (row ushr 6)
                            masks[slot] = masks[slot] or (1L shl (row and 63))
                        }
                        maskSlot += words
                    } else {
                        starts[bucket] = rowSlot
                        for (k in i until end) rows[rowSlot++] = order[k]
                    }
                    bucket++
                    i = end
                }
                columns.add(Column(values, starts, rows, masks, words))
                retained += bytes
            }
            return TableSupportIndex(columns.toTypedArray(), retained)
        }
    }
}
