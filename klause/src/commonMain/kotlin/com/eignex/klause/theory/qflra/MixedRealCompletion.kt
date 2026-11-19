package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.sourceLpPoint
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntDisjointSet
import com.eignex.util.numbers.BigFraction

internal fun completeMixedRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: ExecutionControl,
    onStage: ((String) -> Unit)? = null,
): List<BigFraction>? {
    if (columns !in 1..10000 || rows.size > 100000) return null
    stop.charge(4L * columns)
    if (stop()) return null
    val groups = IntDisjointSet(columns)
    val sizes = IntArray(columns) { 1 }
    val depth = Int.SIZE_BITS - columns.countLeadingZeroBits()
    for (row in rows) {
        stop.charge(8L * (row.columns.size + 1L) * depth)
        if (stop() || row.columns.any { it !in 0 until columns }) return null
        if (!row.coefficients.admittedSourcePoint() || !listOf(row.rhs).admittedSourcePoint()) return null
        val first = row.columns.firstOrNull()
        if (first == null) {
            if (if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs) return null
            continue
        }
        for (column in row.columns) {
            val left = groups.find(first)
            val right = groups.find(column)
            if (left == right) continue
            // Linking the smaller tree bounds every uncompressed walk by log2(columns).
            if (sizes[left] <= sizes[right]) {
                groups.union(left, right)
                sizes[right] += sizes[left]
            } else {
                groups.union(right, left)
                sizes[left] += sizes[right]
            }
        }
    }
    val byRoot = HashMap<Int, MutableList<ExactRationalInequality>>()
    for (row in rows) {
        stop.charge(4L * depth)
        if (stop()) return null
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += row
    }
    stop.charge(4L * columns * depth)
    if (stop()) return null
    val members = groups.groups()
    val point = MutableList(columns) { BigFraction.ZERO }
    for (component in members) {
        stop.charge(4L + component.size)
        if (stop()) return null
        val selected = byRoot[groups.find(component.first())] ?: continue
        onStage?.invoke("RECOVERY_REAL_COMPONENT")
        val local = component.withIndex().associate { it.value to it.index }
        val localRows = ArrayList<ExactRationalInequality>(selected.size)
        for (row in selected) {
            stop.charge(row.columns.size.toLong() + 1L)
            if (stop()) return null
            localRows += ExactRationalInequality(row.columns.map { local.getValue(it) }.toIntArray(),
                row.coefficients, row.rhs, row.strict)
        }
        onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
        val values = sourceLpPoint(localRows, component.size, budget, stop)
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return null
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    return point.takeUnless { stop() }?.also { onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT") }
}
