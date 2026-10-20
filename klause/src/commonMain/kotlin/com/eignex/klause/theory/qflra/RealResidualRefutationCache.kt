package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class RealResidualRefutationCache {
    private var core = emptyMap<RowKey, Int>()
    private var comparisonWork = 0L

    fun clear() {
        core = emptyMap()
        comparisonWork = 0L
    }

    fun find(rows: List<ExactRationalInequality>, stop: Cancellation): IntArray? {
        if (core.isEmpty() || stop()) return null
        stop.charge(core.size.toLong() + 1L)
        if (stop()) return null
        val found = IntArray(core.size) { -1 }
        var remaining = found.size
        for ((index, row) in rows.withIndex()) {
            val key = key(row, stop) ?: return null
            stop.charge(comparisonWork)
            if (stop()) return null
            val slot = core[key] ?: continue
            if (found[slot] >= 0) continue
            found[slot] = index
            if (--remaining == 0) return found.sortedArray().takeUnless { stop() }
        }
        return null
    }

    // The caller supplies only rows of a completed refutation over free residual coordinates.
    fun remember(rows: List<ExactRationalInequality>, support: IntArray, stop: Cancellation) {
        if (support.isEmpty() || support.size > 128 || stop()) return
        stop.charge(8L * support.size + 1L)
        if (stop() || support.any { it !in rows.indices } || support.toSet().size != support.size) return
        var entries = 0L
        var bits = 0L
        val coordinates = HashSet<Int>()
        for (index in support) {
            val row = rows[index]
            entries += row.columns.size
            stop.charge(4L * row.columns.size + 1L)
            if (entries > 512L || stop()) return
            for (column in row.columns) coordinates += column
            if (coordinates.size + support.size > 128) return
            for (number in row.coefficients + row.rhs) {
                val numerator = number.num.magnitudeBitLength()
                val denominator = number.den.magnitudeBitLength()
                stop.charge(1L + (numerator.toLong() + denominator) / Long.SIZE_BITS)
                bits += numerator.toLong() + denominator
                if (numerator > 4096 || denominator > 4096 || bits > 8192L || stop()) return
            }
        }
        stop.charge(8L * entries + 8L * support.size)
        if (stop()) return
        val replacement = LinkedHashMap<RowKey, Int>()
        for (index in support) {
            val row = rows[index]
            val snapshot = ExactRationalInequality(row.columns.copyOf(), row.coefficients.toList(), row.rhs, row.strict)
            val key = key(snapshot, stop) ?: return
            if (key !in replacement) replacement[key] = replacement.size
        }
        if (!stop()) {
            core = replacement
            comparisonWork = 8L * entries + 8L * support.size + bits
        }
    }

    private fun key(row: ExactRationalInequality, stop: Cancellation): RowKey? {
        stop.charge(4L * row.columns.size + 1L)
        if (stop()) return null
        for (number in row.coefficients + row.rhs) {
            stop.charge(1L + (number.num.magnitudeBitLength().toLong() +
                number.den.magnitudeBitLength()) / Long.SIZE_BITS)
            if (stop()) return null
        }
        return RowKey(row).takeUnless { stop() }
    }

    private class RowKey(private val row: ExactRationalInequality) {
        private val hash = 31 * (31 * (31 * row.columns.contentHashCode() + row.coefficients.hashCode()) +
            row.rhs.hashCode()) + row.strict.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is RowKey && row.strict == other.row.strict &&
            row.rhs == other.row.rhs && row.columns.contentEquals(other.row.columns) &&
            row.coefficients == other.row.coefficients
    }
}
