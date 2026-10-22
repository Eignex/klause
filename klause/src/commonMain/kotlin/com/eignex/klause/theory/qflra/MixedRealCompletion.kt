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
            val reduced = if (budget.admitsDimensions(component.size, localRows.size.toLong())) {
                localRows
            } else {
                mergeParallelRealRows(localRows, stop) ?: localRows
            }
            if (stop()) return null
            onStage?.invoke("RECOVERY_REAL_COMPONENT_ROWS_${localRows.size}_${reduced.size}")
            if (!budget.admitsDimensions(component.size, reduced.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return null
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            SourceLp(reduced, component.size, budget).use { owner ->
                val result = owner.solve(stop)
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_${result?.verdict}")
                result?.exactPrimal?.take(component.size)
            }
        }
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return null
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    return point.takeIf { MixedPointArithmetic(stop).satisfies(it, rows) }
        ?.also { onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT") }
}

private fun mergeParallelRealRows(
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
): List<ExactRationalInequality>? {
    val arithmetic = MixedPointArithmetic(stop)
    val strongest = LinkedHashMap<List<Pair<Int, BigFraction>>, ExactRationalInequality>()
    for (row in rows) {
        stop.charge(4L * (row.columns.size + 1L))
        if (stop()) return null
        val first = row.coefficients.firstOrNull { !it.isZero }
        if (first == null) {
            if (if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs) return null
            continue
        }
        // Positive scaling preserves the inequality direction and keeps opposite activities distinct.
        val scale = if (first.signum() < 0) first.negated() else first
        val activity = ArrayList<Pair<Int, BigFraction>>(row.columns.size)
        for (index in row.columns.indices) {
            val coefficient = row.coefficients[index]
            if (coefficient.isZero) continue
            activity += row.columns[index] to (arithmetic.quotient(coefficient, scale) ?: return null)
        }
        val rhs = arithmetic.quotient(row.rhs, scale) ?: return null
        val previous = strongest[activity]
        if (previous != null && !arithmetic.preflight(rhs, previous.rhs)) return null
        if (previous == null || rhs < previous.rhs || (rhs == previous.rhs && row.strict && !previous.strict)) {
            strongest[activity] = ExactRationalInequality(
                activity.map { it.first }.toIntArray(), activity.map { it.second }, rhs, row.strict,
            )
        }
    }
    return strongest.values.toList().takeUnless { stop() }
}
