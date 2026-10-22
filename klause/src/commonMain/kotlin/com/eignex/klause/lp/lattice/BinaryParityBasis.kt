package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

@Suppress("ReturnCount")
internal class BinaryParityWork(private val stop: Cancellation, retainedBytes: Long = 0L) {
    private var visits = 0L
    var allocatedBytes = retainedBytes
        private set
    var refused = false
        private set
    var cancelled = false
        private set

    fun visit(units: Long = 1L): Boolean {
        if (refused || cancelled) return false
        if (units < 0L || units > MAX_WORK - visits) return refuse()
        visits += units
        stop.charge(units)
        cancelled = stop()
        return !cancelled
    }

    fun allocate(entries: Long): Boolean {
        if (entries < 0L || entries > (MAX_BYTES - 64L) / 8L) return refuse()
        val bytes = 64L + entries * 8L
        if (bytes > MAX_BYTES - allocatedBytes) return refuse()
        if (!visit(entries + 1L)) return false
        allocatedBytes += bytes
        return true
    }

    fun refuse(): Boolean {
        refused = true
        return false
    }

    private companion object {
        const val MAX_WORK = 1_048_576L
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}

@Suppress("ReturnCount")
internal class BinaryParityRow private constructor(private val columns: IntArray, val odd: Boolean) {
    val size: Int get() = columns.size
    fun column(index: Int): Int = columns[index]

    companion object {
        fun create(
            columns: IntArray,
            odd: Boolean,
            work: BinaryParityWork,
            size: Int = columns.size,
        ): BinaryParityRow? {
            if (size !in 0..minOf(columns.size, MAX_PARITY_ROW)) {
                work.refuse()
                return null
            }
            if (!work.allocate(size.toLong())) return null
            val ordered = columns.copyOf(size)
            var levels = 1L
            var width = size
            while (width > 1) {
                levels++
                width = width ushr 1
            }
            if (!work.visit(size * levels + 1L)) return null
            ordered.sort()
            var count = 0
            var index = 0
            while (index < size) {
                if (!work.visit()) return null
                val column = ordered[index]
                if (column < 0) return null
                var parity = false
                while (index < size && ordered[index] == column) {
                    if (!work.visit()) return null
                    parity = !parity
                    index++
                }
                if (parity) ordered[count++] = column
            }
            if (!work.allocate(count.toLong())) return null
            return BinaryParityRow(ordered.copyOf(count), odd).takeIf { work.visit() }
        }
    }
}

internal class BinaryParityReduction internal constructor(
    val impossible: Boolean,
    private val support: IntArray,
) {
    val supportSize: Int get() = support.size
    fun supportAt(index: Int): Int = support[index]
}

@Suppress("ReturnCount")
internal class BinaryParityBasis(private val work: BinaryParityWork) {
    private class Row(val columns: IntArray, val odd: Boolean, val support: IntArray)
    private val pivots = if (work.allocate(64L)) MutableIntObjectMap<Row>() else null
    private val origins = if (work.allocate(64L)) IntHashSet() else null
    private var retained = 0L
    val size: Int get() = pivots?.size ?: 0

    fun add(row: BinaryParityRow, origin: Int): BinaryParityReduction? {
        if (origin < 0 || origins == null || !work.allocate(8L)) return null
        if (!origins.add(origin)) {
            work.refuse()
            return null
        }
        val reduced = reduce(row, origin) ?: return null
        if (reduced.columns.isNotEmpty()) {
            val entries = reduced.columns.size.toLong() + reduced.support.size
            if (entries > MAX_RETAINED - retained || !work.allocate(16L)) {
                work.refuse()
                return null
            }
            pivots?.put(reduced.columns[0], reduced) ?: return null
            retained += entries
        }
        return result(reduced)
    }

    fun reduce(row: BinaryParityRow): BinaryParityReduction? = reduce(row, null)?.let(::result)

    private fun reduce(row: BinaryParityRow, origin: Int?): Row? {
        if (pivots == null || !work.allocate(row.size.toLong())) return null
        var columns = IntArray(row.size)
        for (index in columns.indices) {
            if (!work.visit()) return null
            columns[index] = row.column(index)
        }
        var support = EMPTY
        if (origin != null) {
            if (!work.allocate(1L)) return null
            support = intArrayOf(origin)
        }
        var odd = row.odd
        while (columns.isNotEmpty()) {
            if (!work.visit()) return null
            val pivot = pivots[columns[0]] ?: break
            columns = xor(columns, pivot.columns) ?: return null
            support = xor(support, pivot.support) ?: return null
            odd = odd xor pivot.odd
        }
        if (!work.allocate(16L)) return null
        return Row(columns, odd, support)
    }

    private fun result(row: Row): BinaryParityReduction? {
        if (!work.allocate(8L)) return null
        return BinaryParityReduction(row.columns.isEmpty() && row.odd, row.support)
    }

    private fun xor(left: IntArray, right: IntArray): IntArray? {
        val size = left.size.toLong() + right.size
        if (size > MAX_PARITY_ROW || !work.allocate(size)) {
            work.refuse()
            return null
        }
        val output = IntArray(size.toInt())
        var l = 0
        var r = 0
        var count = 0
        while (l < left.size || r < right.size) {
            if (!work.visit(3L)) return null
            when {
                r == right.size || (l < left.size && left[l] < right[r]) -> output[count++] = left[l++]
                l == left.size || right[r] < left[l] -> output[count++] = right[r++]
                else -> {
                    l++
                    r++
                }
            }
        }
        if (!work.allocate(count.toLong())) return null
        return output.copyOf(count)
    }

    private companion object {
        const val MAX_RETAINED = 131_072L
        val EMPTY = intArrayOf()
    }
}

internal const val MAX_PARITY_ROW = 4096
