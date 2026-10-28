package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.table.Table
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

internal object CyclicTableComposition {
    private class Tuple(val values: LongArray) {
        override fun hashCode(): Int = values.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Tuple && values.contentEquals(other.values)
    }

    private class Projection(
        val targetColumns: IntArray,
        val supports: Map<Tuple, Set<Tuple>>,
    )

    fun intersect(
        target: Table,
        left: Table,
        right: Table,
        visit: () -> Boolean,
        cells: Int,
        bridgeVariables: IntArray? = null,
    ): ExtensionalIntersection? {
        val bridge = bridgeVariables ?: sharedOutsideTarget(target, left, right, visit) ?: return null
        if (bridge.isEmpty()) return null
        val storage = Storage(cells)
        val a = project(target, left, bridge, visit, storage) ?: return null
        val b = project(target, right, bridge, visit, storage) ?: return null
        val kept = LongArrayList()
        for (row in 0 until target.numTuples) {
            val keyA = tuple(target, row, a.targetColumns, visit) ?: return null
            val keyB = tuple(target, row, b.targetColumns, visit) ?: return null
            val supportsA = a.supports[keyA].orEmpty()
            val supportsB = b.supports[keyB].orEmpty()
            val small = if (supportsA.size <= supportsB.size) supportsA else supportsB
            val large = if (supportsA.size <= supportsB.size) supportsB else supportsA
            var supported = false
            for (support in small) {
                repeat(bridge.size + 1) { if (!visit()) return null }
                if (support in large) {
                    supported = true
                    break
                }
            }
            if (!supported) continue
            for (column in target.xs.indices) {
                if (!visit()) return null
                kept.add(target.tuples[row * target.arity + column])
            }
        }
        if (kept.size == target.tuples.size) return null
        return if (kept.isEmpty()) ExtensionalIntersection.Refuted
        else ExtensionalIntersection.Restricted(Table(target.xs, kept.toLongArray()))
    }

    fun sharedOutsideTarget(target: Table, left: Table, right: Table, visit: () -> Boolean): IntArray? {
        val bridge = IntArrayList()
        for (variable in left.xs) {
            val other = position(right.xs, variable, visit) ?: return null
            val own = position(target.xs, variable, visit) ?: return null
            if (other >= 0 && own < 0) bridge.add(variable)
        }
        return bridge.toIntArray()
    }

    private class Storage(var remaining: Int) {
        fun retain(cells: Int): Boolean {
            if (cells > remaining) return false
            remaining -= cells
            return true
        }
    }

    private fun project(
        target: Table,
        source: Table,
        bridge: IntArray,
        visit: () -> Boolean,
        storage: Storage,
    ): Projection? {
        val sourceColumns = IntArrayList()
        val targetColumns = IntArrayList()
        for (column in source.xs.indices) {
            val other = position(target.xs, source.xs[column], visit) ?: return null
            if (other >= 0) {
                sourceColumns.add(column)
                targetColumns.add(other)
            }
        }
        if (sourceColumns.isEmpty()) return null
        val bridgeColumns = IntArray(bridge.size)
        for (i in bridge.indices) bridgeColumns[i] = position(source.xs, bridge[i], visit) ?: return null
        val columns = sourceColumns.toIntArray()
        val supports = HashMap<Tuple, MutableSet<Tuple>>()
        for (row in 0 until source.numTuples) {
            val key = tuple(source, row, columns, visit) ?: return null
            val support = tuple(source, row, bridgeColumns, visit) ?: return null
            val values = supports[key] ?: run {
                if (!storage.retain(columns.size + 1)) return null
                HashSet<Tuple>().also { supports[key] = it }
            }
            if (support !in values) {
                if (!storage.retain(bridge.size + 1)) return null
                values.add(support)
            }
        }
        return Projection(targetColumns.toIntArray(), supports)
    }

    private fun position(scope: IntArray, variable: Int, visit: () -> Boolean): Int? {
        for (i in scope.indices) {
            if (!visit()) return null
            if (scope[i] == variable) return i
        }
        return -1
    }

    private fun tuple(table: Table, row: Int, columns: IntArray, visit: () -> Boolean): Tuple? {
        val values = LongArray(columns.size)
        for (i in columns.indices) {
            if (!visit()) return null
            values[i] = table.tuples[row * table.arity + columns[i]]
        }
        return Tuple(values)
    }
}
