package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.LpExactSupport
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntDisjointSet

internal typealias MixedRealCompletion = (
    Int, List<ExactRationalInequality>, Cancellation, ((IntArray) -> Unit)?,
) -> List<BigFraction>?

internal fun completeMixedRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    onInfeasible: ((IntArray) -> Unit)? = null,
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
    val byRoot = HashMap<Int, MutableList<Int>>()
    for (rowIndex in rows.indices) {
        val row = rows[rowIndex]
        stop.charge(4L)
        if (stop()) return null
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += rowIndex
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
        for (rowIndex in selected) {
            val row = rows[rowIndex]
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
            if (result is RealDifferenceSystem.Result.Infeasible) {
                val support = result.cycleEdges
                onStage?.invoke("RECOVERY_REAL_COMPONENT_GRAPH_CONFLICT")
                if (support != null) {
                    stop.charge(support.size.toLong() + 1L)
                    if (!stop() && support.isNotEmpty() && support.all { it in selected.indices }) {
                        onInfeasible?.invoke(IntArray(support.size) { selected[support[it]] })
                    }
                }
                return null
            }
            (result as? RealDifferenceSystem.Result.Feasible)?.point
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            val reduced = mergeParallelRealRows(localRows, stop)
                ?: MixedRealRows(localRows, IntArray(localRows.size) { it })
            if (stop()) return null
            val conflict = reduced.infeasibleRows
            if (conflict != null) {
                stop.charge(conflict.size.toLong())
                if (stop()) return null
                onStage?.invoke("RECOVERY_REAL_COMPONENT_PARALLEL_CONFLICT")
                if (!stop()) onInfeasible?.invoke(IntArray(conflict.size) { selected[conflict[it]] })
                return null
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_ROWS_${localRows.size}_${reduced.rows.size}")
            if (!budget.admitsDimensions(component.size, reduced.rows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return null
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            SourceLp(reduced.rows, component.size, budget).use { owner ->
                val result = owner.solve(stop)
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_${result?.verdict}")
                if (result?.verdict == LpVerdict.INFEASIBLE && onInfeasible != null && !stop()) {
                    val support = residualConflictRows(result.conflictSupport, component.size, reduced.rows.size, stop)
                    if (support != null && !stop()) {
                        stop.charge(support.size.toLong())
                        val original = IntArray(support.size) { selected[reduced.origins[support[it]]] }
                        onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_CONFLICT")
                        if (!stop()) onInfeasible(original)
                    }
                }
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

private class MixedRealRows(
    val rows: List<ExactRationalInequality>,
    val origins: IntArray,
    val infeasibleRows: IntArray? = null,
)

private fun mergeParallelRealRows(
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
): MixedRealRows? {
    val arithmetic = MixedPointArithmetic(stop)
    val strongest = LinkedHashMap<List<Pair<Int, BigFraction>>, IndexedValue<ExactRationalInequality>>()
    for (rowIndex in rows.indices) {
        val row = rows[rowIndex]
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
        val previous = strongest[activity]?.value
        if (previous != null && !arithmetic.preflight(rhs, previous.rhs)) return null
        if (previous == null || rhs < previous.rhs || (rhs == previous.rhs && row.strict && !previous.strict)) {
            strongest[activity] = IndexedValue(rowIndex, ExactRationalInequality(
                activity.map { it.first }.toIntArray(), activity.map { it.second }, rhs, row.strict,
            ))
        }
        stop.charge(activity.size.toLong() + 1L)
        if (stop()) return null
        val opposite = strongest[activity.map { (column, coefficient) -> column to coefficient.negated() }]
        if (opposite != null) {
            val upper = strongest.getValue(activity)
            if (!arithmetic.preflight(upper.value.rhs, opposite.value.rhs)) return null
            val gap = upper.value.rhs + opposite.value.rhs
            if (!arithmetic.admitted(gap)) return null
            if (gap < BigFraction.ZERO || (gap.isZero && (upper.value.strict || opposite.value.strict))) {
                return MixedRealRows(emptyList(), intArrayOf(), intArrayOf(upper.index, opposite.index))
            }
        }
    }
    stop.charge(2L * strongest.size)
    if (stop()) return null
    return MixedRealRows(strongest.values.map { it.value }, strongest.values.map { it.index }.toIntArray())
}

private fun residualConflictRows(
    support: LpExactSupport?,
    columns: Int,
    rows: Int,
    stop: Cancellation,
): IntArray? {
    if (support == null || stop()) return null
    val state = support.state
    if (state.depth != 0 || state.model.n != columns || state.model.m != rows || support.rows.isEmpty()) return null
    stop.charge(4L * (support.rows.size + support.sides.size) + support.rows.size.toLong() * support.sides.size)
    if (stop()) return null
    for ((index, metadata) in support.rows) {
        if (index !in 0 until rows || state.model.row(index) != metadata || !state.rows.row(index).active) return null
    }
    for (side in support.sides) {
        // Structural columns are free; only the source inequalities' root slack bounds can justify this proof.
        if (side.column !in columns until columns + rows || side.upper) return null
        val active = state.activeSide(side.column, side.upper) ?: return null
        if (active.witness >= 0L || active.witness != side.witness || active.side != side.side) return null
        if (support.rows.none { it.first == side.column - columns }) return null
    }
    return support.rows.map { it.first }.toIntArray().takeUnless { stop() }
}
