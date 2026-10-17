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
        var compacted = false
        val values = if (graph != null) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_GRAPH")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return null
            (result as? RealDifferenceSystem.Result.Feasible)?.point
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            val numericalRows = if (budget.admitsDimensions(component.size, localRows.size.toLong())) {
                localRows
            } else {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTION_ATTEMPT")
                val reduced = compactMixedRealRows(component.size, localRows, stop)
                if (reduced == null) {
                    onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTION_DECLINED")
                } else {
                    compacted = true
                    onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTED_${localRows.size}_${reduced.size}")
                }
                reduced
            }
            if (stop()) return null
            if (numericalRows == null || !budget.admitsDimensions(component.size, numericalRows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return null
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            SourceLp(numericalRows, component.size, budget).use { owner ->
                owner.solve(stop)?.exactPrimal?.take(component.size)
            }
        }
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return null
        }
        if (compacted) {
            stop.charge(localRows.sumOf { it.columns.size.toLong() + 1L })
            if (stop() || !MixedPointArithmetic(stop).satisfies(values, localRows)) return null
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    return point.takeUnless { stop() }?.also { onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT") }
}

internal fun compactMixedRealRows(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
): List<ExactRationalInequality>? {
    if (columns <= 0 || columns.toLong() * columns > 65536L || rows.size > 65536) return null
    var terms = 0L
    for (row in rows) {
        stop.charge(1L)
        terms += row.columns.size
        if (stop() || terms + rows.size > 65536L) return null
    }
    // Covers live component views, ordering buffers, survivor references and bounded rational scratch.
    val cells = 96L * rows.size + 4L * terms + 16L * columns + 8192L
    if (cells > 65536L) return null
    stop.charge(4L * rows.size + 2L * columns + 1L)
    if (stop()) return null
    val positions = IntArray(columns) { -1 }
    val retained = ArrayList<OrderedRealRow>()
    val arithmetic = MixedPointArithmetic(stop)
    var visits = 0L
    for (row in rows) {
        stop.charge(2L * columns + 8L * row.columns.size + 1L)
        if (stop()) return null
        val candidate = orderedRealRow(row, positions, arithmetic, stop) ?: return null
        var matched = false
        for (index in retained.indices) {
            if (++visits > 65536L) return null
            when (compareParallelRows(candidate, retained[index], arithmetic, stop)) {
                ParallelRowMatch.DIFFERENT -> Unit
                ParallelRowMatch.DECLINED -> return null
                ParallelRowMatch.RETAIN -> matched = true
                ParallelRowMatch.REPLACE -> {
                    retained[index] = candidate
                    matched = true
                }
            }
            if (matched) break
        }
        if (!matched) retained += candidate
    }
    stop.charge(retained.size.toLong() + 1L)
    if (stop()) return null
    return retained.map { it.source }.takeUnless { stop() }
}

private class OrderedRealRow(val source: ExactRationalInequality, val order: IntArray)

private fun orderedRealRow(
    row: ExactRationalInequality,
    positions: IntArray,
    arithmetic: MixedPointArithmetic,
    stop: Cancellation,
): OrderedRealRow? {
    if (row.columns.isEmpty() || row.columns.size != row.coefficients.size ||
        !arithmetic.admitted(row.rhs) || row.coefficients.any { it.isZero || !arithmetic.admitted(it) }
    ) return null
    for (index in row.columns.indices) {
        val column = row.columns[index]
        if (column !in positions.indices || positions[column] >= 0) return null
        positions[column] = index
    }
    if (stop()) return null
    val order = IntArray(row.columns.size)
    var size = 0
    for (column in positions.indices) {
        val index = positions[column]
        if (index < 0) continue
        order[size++] = index
        positions[column] = -1
    }
    return OrderedRealRow(row, order).takeUnless { stop() }
}

private enum class ParallelRowMatch { DIFFERENT, DECLINED, RETAIN, REPLACE }

private fun compareParallelRows(
    candidate: OrderedRealRow,
    previous: OrderedRealRow,
    arithmetic: MixedPointArithmetic,
    stop: Cancellation,
): ParallelRowMatch {
    stop.charge(2L * candidate.order.size + 1L)
    if (stop()) return ParallelRowMatch.DECLINED
    if (candidate.order.size != previous.order.size) return ParallelRowMatch.DIFFERENT
    val row = candidate.source
    val kept = previous.source
    for (index in candidate.order.indices) {
        if (row.columns[candidate.order[index]] != kept.columns[previous.order[index]]) {
            return ParallelRowMatch.DIFFERENT
        }
    }
    val scale = arithmetic.quotient(row.coefficients[candidate.order[0]], kept.coefficients[previous.order[0]])
        ?: return ParallelRowMatch.DECLINED
    if (scale <= BigFraction.ZERO) return ParallelRowMatch.DIFFERENT
    for (index in 1 until candidate.order.size) {
        val difference = arithmetic.subtractProduct(row.coefficients[candidate.order[index]], scale,
            kept.coefficients[previous.order[index]]) ?: return ParallelRowMatch.DECLINED
        if (!difference.isZero) return ParallelRowMatch.DIFFERENT
    }
    val difference = arithmetic.subtractProduct(row.rhs, scale, kept.rhs) ?: return ParallelRowMatch.DECLINED
    return if (difference < BigFraction.ZERO || difference.isZero && row.strict && !kept.strict) {
        ParallelRowMatch.REPLACE
    } else {
        ParallelRowMatch.RETAIN
    }
}
