package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal class UnitEqualityBasis(private val stop: Cancellation) {
    private val pivots = HashMap<Int, Reduction>()
    private var retained = 0L
    private var visits = 0L
    var decline: Decline? = null
        private set
    val declined: Boolean get() = decline != null
    val size: Int get() = pivots.size

    enum class Decline { STORAGE, WORK }

    class Reduction(
        val columns: IntArray,
        val coefficients: LongArray,
        val bound: Long,
        val support: IntArray,
    )

    fun add(columns: IntArray, coefficients: LongArray, bound: Long, origin: Int): Reduction? {
        if (origin < 0) return null
        val reduced = reduce(columns, coefficients, bound, origin) ?: return null
        if (reduced.columns.isEmpty()) return reduced
        val leading = reduced.coefficients[0]
        if (leading != 1L && leading != -1L) return reduced
        val cells = cells(reduced.columns.size, reduced.support.size)
        if (!admit(2L * cells) || !visit(cells)) return null
        val normalized = try {
            if (leading == 1L) {
                Reduction(reduced.columns.copyOf(), reduced.coefficients.copyOf(), reduced.bound,
                    reduced.support.copyOf())
            } else {
                Reduction(reduced.columns.copyOf(), LongArray(reduced.coefficients.size) {
                    subExact(0L, reduced.coefficients[it])
                }, subExact(0L, reduced.bound), reduced.support.copyOf())
            }
        } catch (_: CheckedLongOverflowException) {
            return reduced
        }
        if (stop()) return null
        pivots[normalized.columns[0]] = normalized
        retained += cells
        return reduced
    }

    fun reduce(columns: IntArray, coefficients: LongArray, bound: Long): Reduction? =
        reduce(columns, coefficients, bound, null)

    private fun reduce(
        columns: IntArray,
        coefficients: LongArray,
        bound: Long,
        origin: Int?,
    ): Reduction? {
        if (columns.size != coefficients.size || columns.size > MAX_ROW ||
            !admit(8L * columns.size + 8L) || !visit(4L * columns.size + 1L)
        ) return null
        val values = HashMap<Int, Long>()
        val support = LinkedHashSet<Int>()
        if (origin != null) support += origin
        var rhs = bound
        try {
            for (index in columns.indices) {
                val column = columns[index]
                if (column < 0 || stop()) return null
                val value = addExact(values[column] ?: 0L, coefficients[index])
                if (value == 0L) values.remove(column) else values[column] = value
            }
            var previous = -1
            while (true) {
                if (!visit(values.size.toLong() + 1L)) return null
                var column = Int.MAX_VALUE
                for (candidate in values.keys) if (candidate > previous && candidate < column) column = candidate
                if (column == Int.MAX_VALUE) break
                previous = column
                val pivot = pivots[column] ?: continue
                if (!admit(8L * (values.size + pivot.columns.size + support.size + pivot.support.size) + 8L) ||
                    !visit(5L * pivot.columns.size + pivot.support.size + 2L)
                ) return null
                val multiplier = values.getValue(column)
                rhs = subExact(rhs, mulExact(multiplier, pivot.bound))
                for (index in pivot.columns.indices) {
                    if (stop()) return null
                    val target = pivot.columns[index]
                    val value = subExact(values[target] ?: 0L, mulExact(multiplier, pivot.coefficients[index]))
                    if (value == 0L) values.remove(target) else values[target] = value
                }
                support.addAll(pivot.support.toList())
                if (values.size > MAX_ROW || support.size > MAX_ROW) return null
            }
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        if (!admit(8L * (values.size + support.size) + 8L) ||
            !visit((values.size.toLong() + 1L) * 16L + support.size)
        ) return null
        val ordered = values.keys.sorted().toIntArray()
        val result = Reduction(ordered, LongArray(ordered.size) { values.getValue(ordered[it]) }, rhs,
            support.toIntArray())
        return result.takeUnless { stop() }
    }

    private fun cells(columns: Int, support: Int): Long = 2L * columns + support + 4L

    private fun admit(temporary: Long): Boolean {
        if (declined) return false
        if (temporary > MAX_ENTRIES - retained) {
            decline = Decline.STORAGE
            return false
        }
        return true
    }

    private fun visit(work: Long): Boolean {
        if (declined) return false
        if (work > MAX_WORK - visits) {
            decline = Decline.WORK
            return false
        }
        visits += work
        stop.charge(work)
        return !stop()
    }

    companion object {
        private const val MAX_ROW = 4_096
        private const val MAX_ENTRIES = 65_536L
        private const val MAX_WORK = 1_048_576L
    }
}
