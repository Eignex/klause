package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
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
): List<BigFraction>? = if (columns <= 0) null else
    (solveRealComponents(columns, rows, budget, stop, onStage) as? RealComponents.Point)?.values

internal fun refuteRealResidualComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): Boolean {
    if (budget.context.certificationPolicy !== ProductionLpCertificationPolicy) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_ATTEMPT")
    val result = solveRealComponents(columns, rows, budget, stop, onStage)
    if (stop() || result !== RealComponents.Refuted) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_REFUTED")
    return !stop()
}

private sealed interface RealComponents {
    class Point(val values: List<BigFraction>) : RealComponents
    data object Refuted : RealComponents
    data object Declined : RealComponents
}

private fun solveRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
): RealComponents {
    if (columns < 0 || columns.toLong() * columns > 65536L || rows.size > 65536) return RealComponents.Declined
    stop.charge(4L * columns + rows.size + 1L)
    if (stop()) return RealComponents.Declined
    val work = MixedPointArithmetic(stop)
    val used = BooleanArray(columns)
    val normalized = ArrayList<ExactRationalInequality>(rows.size)
    var entries = 0L
    var falseConstant = false
    for (row in rows) {
        entries += row.columns.size + 1L
        stop.charge(8L * (row.columns.size + 1L))
        if (entries > 65536L || stop() || row.columns.size != row.coefficients.size ||
            row.columns.any { it !in 0 until columns } ||
            row.columns.indices.any { it > 0 && row.columns[it - 1] >= row.columns[it] } ||
            !work.admitted(row.rhs) || !row.coefficients.all(work::admitted)
        ) return RealComponents.Declined
        val nonzero = row.columns.indices.filter { !row.coefficients[it].isZero }
        if (nonzero.isEmpty()) {
            falseConstant = falseConstant || if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs
        } else {
            val selected = nonzero.map { row.columns[it] }.toIntArray()
            for (column in selected) used[column] = true
            normalized += ExactRationalInequality(selected, nonzero.map { row.coefficients[it] }, row.rhs, row.strict)
        }
    }
    if (stop()) return RealComponents.Declined
    if (falseConstant) return RealComponents.Refuted
    val original = used.indices.filter { used[it] }
    stop.charge(4L * columns + 4L * entries)
    if (stop()) return RealComponents.Declined
    val local = IntArray(columns) { -1 }
    for (index in original.indices) local[original[index]] = index
    val compact = normalized.map { row ->
        ExactRationalInequality(row.columns.map { local[it] }.toIntArray(), row.coefficients, row.rhs, row.strict)
    }
    val result = solveCompactRealComponents(original.size, compact, budget, stop, onStage)
    if (stop() || result !is RealComponents.Point) return if (stop()) RealComponents.Declined else result
    stop.charge(2L * columns + original.size)
    if (stop()) return RealComponents.Declined
    val point = MutableList(columns) { BigFraction.ZERO }
    for (index in original.indices) point[original[index]] = result.values[index]
    return if (stop()) RealComponents.Declined else RealComponents.Point(point)
}

private fun solveCompactRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
): RealComponents {
    // Each parent can change at most once per union; the batch covers all compressed walks.
    stop.charge(8L * columns * columns + columns)
    if (stop()) return RealComponents.Declined
    val groups = IntDisjointSet(columns)
    for (row in rows) {
        stop.charge(8L * (row.columns.size + 1L))
        if (stop()) return RealComponents.Declined
        val first = row.columns.firstOrNull() ?: continue
        for (column in row.columns) groups.union(first, column)
    }
    val byRoot = HashMap<Int, MutableList<ExactRationalInequality>>()
    for (row in rows) {
        stop.charge(4L)
        if (stop()) return RealComponents.Declined
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += row
    }
    stop.charge(4L * columns)
    if (stop()) return RealComponents.Declined
    val members = groups.groups()
    val point = MutableList(columns) { BigFraction.ZERO }
    for (component in members) {
        stop.charge(4L + component.size)
        if (stop()) return RealComponents.Declined
        val selected = byRoot[groups.find(component.first())] ?: continue
        onStage?.invoke("RECOVERY_REAL_COMPONENT")
        val local = component.withIndex().associate { it.value to it.index }
        val localRows = ArrayList<ExactRationalInequality>(selected.size)
        for (row in selected) {
            stop.charge(row.columns.size.toLong() + 1L)
            if (stop()) return RealComponents.Declined
            localRows += ExactRationalInequality(row.columns.map { local.getValue(it) }.toIntArray(),
                row.coefficients, row.rhs, row.strict)
        }
        val graph = RealDifferenceSystem.prepareRows(component.size, localRows, stop)
        if (stop()) return RealComponents.Declined
        val values = if (graph != null) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_GRAPH")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return RealComponents.Declined
            when (result) {
                is RealDifferenceSystem.Result.Feasible -> result.point
                is RealDifferenceSystem.Result.Infeasible -> return RealComponents.Refuted
                else -> null
            }
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            if (!budget.admitsDimensions(component.size, localRows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return RealComponents.Declined
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            val result = SourceLp(localRows, component.size, budget).use { owner -> owner.solve(stop) }
            if (stop()) return RealComponents.Declined
            if (result?.verdict == LpVerdict.INFEASIBLE &&
                budget.context.certificationPolicy === ProductionLpCertificationPolicy
            ) return RealComponents.Refuted
            result?.exactPrimal?.take(component.size)
        }
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return RealComponents.Declined
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    if (stop()) return RealComponents.Declined
    onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT")
    return if (stop()) RealComponents.Declined else RealComponents.Point(point)
}
