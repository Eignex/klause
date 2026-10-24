package com.eignex.klause.factor.table.internals

import com.eignex.klause.util.argsortBy

internal class ExtensionalSupportIndex private constructor(val columns: Array<Column>, val retainedBytes: Long) {
    internal class Column(
        private val values: LongArray,
        private val starts: IntArray,
        private val rows: IntArray,
        private val masks: LongArray,
        private val words: Int,
        val retainedBytes: Long,
    ) {
        fun valueCount(lower: Long, upper: Long): Int {
            var lo = 0
            var hi = values.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (values[mid] <= upper) lo = mid + 1 else hi = mid
            }
            return (lo - lowerBound(lower)).coerceAtLeast(0)
        }

        fun forEachValue(lower: Long, upper: Long, visit: (Long, Int) -> Unit) {
            var bucket = lowerBound(lower)
            while (bucket < values.size && values[bucket] <= upper) {
                visit(values[bucket], bucket)
                bucket++
            }
        }

        fun forEachWord(bucket: Int, visit: (Int, Long) -> Unit, poll: () -> Unit) {
            val start = starts[bucket]
            if (start >= 0) {
                for (i in start until sparseLimit(bucket)) {
                    poll()
                    val row = rows[i]
                    visit(row ushr 6, 1L shl (row and 63))
                }
            } else {
                val base = -start - 1
                for (i in 0 until words) {
                    poll()
                    if (masks[base + i] != 0L) visit(i, masks[base + i])
                }
            }
        }

        fun countLive(value: Long, live: (Int) -> Long, visit: () -> Boolean): Int? {
            val bucket = lowerBound(value)
            if (bucket == values.size || values[bucket] != value) return 0
            val start = starts[bucket]
            var count = 0
            if (start >= 0) {
                for (i in start until sparseLimit(bucket)) {
                    if (!visit()) return null
                    val row = rows[i]
                    if (live(row ushr 6) and (1L shl (row and 63)) != 0L) count++
                }
            } else {
                val base = -start - 1
                for (i in 0 until words) {
                    if (!visit()) return null
                    count += (live(i) and masks[base + i]).countOneBits()
                }
            }
            return count
        }

        private fun sparseLimit(bucket: Int): Int {
            var end = bucket + 1
            while (end < starts.size && starts[end] < 0) end++
            return if (end < starts.size) starts[end] else rows.size
        }

        fun forEachRow(lower: Long, upper: Long, visit: (Int) -> Unit, poll: () -> Unit = {}) {
            var bucket = lowerBound(lower)
            while (bucket < values.size && values[bucket] <= upper) {
                poll()
                val start = starts[bucket]
                if (start >= 0) {
                    val limit = sparseLimit(bucket)
                    for (i in start until limit) {
                        poll()
                        visit(rows[i])
                    }
                } else {
                    val base = -start - 1
                    for (i in 0 until words) {
                        poll()
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
        const val MAX_BYTES = 32L * 1024 * 1024

        fun build(
            tuples: LongArray,
            arity: Int,
            maxBytes: Long = MAX_BYTES,
            poll: () -> Unit = {},
        ): ExtensionalSupportIndex? {
            val columns = ArrayList<Column>(arity)
            var retained = 0L
            for (col in 0 until arity) {
                val column = buildColumn(tuples.size / arity, maxBytes - retained, poll) { row ->
                    tuples[row * arity + col]
                } ?: return null
                columns.add(column)
                retained += column.retainedBytes
            }
            poll()
            return ExtensionalSupportIndex(columns.toTypedArray(), retained)
        }

        fun buildColumn(
            count: Int,
            maxBytes: Long = MAX_BYTES,
            poll: () -> Unit = {},
            value: (Int) -> Long,
        ): Column? {
            poll()
            if (count.toLong() * 8 > maxBytes) return null
            val order = argsortBy(count) { a, b ->
                poll()
                value(a).compareTo(value(b))
            }
            val words = ((count.toLong() + 63) ushr 6).toInt()
            var buckets = 0
            var sparse = 0
            var dense = 0
            var i = 0
            while (i < count) {
                poll()
                var end = i + 1
                while (end < count && value(order[end]) == value(order[i])) {
                    poll()
                    end++
                }
                buckets++
                if (words.toLong() * 8 < (end - i).toLong() * 4) dense += words else sparse += end - i
                i = end
            }
            val bytes = buckets.toLong() * 12 + sparse.toLong() * 4 + dense.toLong() * 8
            if (bytes + count.toLong() * 8 > maxBytes) return null
            poll()
            val values = LongArray(buckets)
            val starts = IntArray(buckets)
            val rows = IntArray(sparse)
            val masks = LongArray(dense)
            var bucket = 0
            var rowSlot = 0
            var maskSlot = 0
            i = 0
            while (i < count) {
                poll()
                val key = value(order[i])
                var end = i + 1
                while (end < count && value(order[end]) == key) {
                    poll()
                    end++
                }
                values[bucket] = key
                if (words.toLong() * 8 < (end - i).toLong() * 4) {
                    starts[bucket] = -maskSlot - 1
                    for (k in i until end) {
                        poll()
                        val row = order[k]
                        val slot = maskSlot + (row ushr 6)
                        masks[slot] = masks[slot] or (1L shl (row and 63))
                    }
                    maskSlot += words
                } else {
                    starts[bucket] = rowSlot
                    for (k in i until end) {
                        poll()
                        rows[rowSlot++] = order[k]
                    }
                }
                bucket++
                i = end
            }
            poll()
            return Column(values, starts, rows, masks, words, bytes)
        }
    }
}
