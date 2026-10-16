package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntDisjointSet

internal fun completeMixedRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): List<BigFraction>? {
    if (columns <= 0 || columns.toLong() * columns > 65536L) return null
    // Each parent can change at most once per union; the batch covers all compressed walks.
    stop.charge(8L * columns * columns + columns)
    if (stop()) return null
    val groups = IntDisjointSet(columns)
    var entries = 0L
    for (row in rows) {
        entries += row.columns.size + 1L
        stop.charge(8L * (row.columns.size + 1L))
        if (entries > 65536L || stop() || row.columns.any { it !in 0 until columns }) return null
        if (!row.coefficients.admittedSourcePoint() || !listOf(row.rhs).admittedSourcePoint()) return null
        val first = row.columns.firstOrNull()
        if (first == null) {
            if (if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs) return null
            continue
        }
        for (column in row.columns) groups.union(first, column)
    }
    val byRoot = HashMap<Int, MutableList<ExactRationalInequality>>()
    for (row in rows) {
        stop.charge(4L)
        if (stop()) return null
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += row
    }
    stop.charge(4L * columns)
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
        val graph = RealDifferenceSystem.prepareRows(component.size, localRows, stop)
        if (stop()) return null
        val values = if (graph != null) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_GRAPH")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return null
            (result as? RealDifferenceSystem.Result.Feasible)?.point
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            if (!budget.admitsDimensions(component.size, localRows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return null
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            SourceLp(localRows, component.size, budget).use { owner ->
                owner.solve(stop)?.exactPrimal?.take(component.size)
            }
        }
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return null
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    return point.takeUnless { stop() }?.also { onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT") }
}
