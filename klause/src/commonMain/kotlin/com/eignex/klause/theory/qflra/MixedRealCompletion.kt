package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.LpVerdict
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
): List<BigFraction>? = (checkMixedRealComponents(columns, rows, budget, stop, onStage = onStage)
    as? MixedPointCompletionResult.Point)?.values

internal fun checkMixedRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    originCells: Long = 0L,
    onStage: ((String) -> Unit)? = null,
): MixedPointCompletionResult {
    if (columns <= 0 || columns.toLong() * columns > 65536L || originCells !in 0L..65536L) {
        return MixedPointCompletionResult.Declined
    }
    // Each parent can change at most once per union; the batch covers all compressed walks.
    stop.charge(8L * columns * columns + columns)
    if (stop()) return MixedPointCompletionResult.Declined
    val groups = IntDisjointSet(columns)
    var entries = 0L
    for (row in rows) {
        entries += row.columns.size + 1L
        stop.charge(8L * (row.columns.size + 1L))
        if (entries > 65536L || stop() || row.columns.any { it !in 0 until columns }) {
            return MixedPointCompletionResult.Declined
        }
        if (!row.coefficients.admittedSourcePoint() || !listOf(row.rhs).admittedSourcePoint()) {
            return MixedPointCompletionResult.Declined
        }
        val first = row.columns.firstOrNull()
        if (first == null) {
            if (if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs) {
                return if (stop()) MixedPointCompletionResult.Declined else MixedPointCompletionResult.Refuted
            }
            continue
        }
        for (column in row.columns) groups.union(first, column)
    }
    val byRoot = HashMap<Int, MutableList<ExactRationalInequality>>()
    for (row in rows) {
        stop.charge(4L)
        if (stop()) return MixedPointCompletionResult.Declined
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += row
    }
    stop.charge(4L * columns)
    if (stop()) return MixedPointCompletionResult.Declined
    val members = groups.groups()
    val point = MutableList(columns) { BigFraction.ZERO }
    for (component in members) {
        stop.charge(4L + component.size)
        if (stop()) return MixedPointCompletionResult.Declined
        val selected = byRoot[groups.find(component.first())] ?: continue
        onStage?.invoke("RECOVERY_REAL_COMPONENT")
        val local = component.withIndex().associate { it.value to it.index }
        val localRows = ArrayList<ExactRationalInequality>(selected.size)
        for (row in selected) {
            stop.charge(row.columns.size.toLong() + 1L)
            if (stop()) return MixedPointCompletionResult.Declined
            localRows += ExactRationalInequality(row.columns.map { local.getValue(it) }.toIntArray(),
                row.coefficients, row.rhs, row.strict)
        }
        val graph = RealDifferenceSystem.prepareRows(component.size, localRows, stop)
        if (stop()) return MixedPointCompletionResult.Declined
        var compacted = false
        val values = if (graph != null) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_GRAPH")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return MixedPointCompletionResult.Declined
            if (result is RealDifferenceSystem.Result.Infeasible) return MixedPointCompletionResult.Refuted
            (result as? RealDifferenceSystem.Result.Feasible)?.point
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            val numericalRows = if (budget.admitsDimensions(component.size, localRows.size.toLong())) {
                localRows
            } else {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTION_ATTEMPT")
                val reduced = compactMixedRealRows(component.size, localRows, stop, originCells)
                if (reduced == null) {
                    onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTION_DECLINED")
                } else {
                    compacted = true
                    onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTED_${localRows.size}_${reduced.size}")
                }
                reduced
            }
            if (stop()) return MixedPointCompletionResult.Declined
            if (numericalRows == null || !budget.admitsDimensions(component.size, numericalRows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return MixedPointCompletionResult.Declined
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            val completed = SourceLp(numericalRows, component.size, budget).use { owner ->
                val result = owner.solve(stop)
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_${result?.verdict?.name ?: "NO_RESULT"}")
                when {
                    stop() -> MixedPointCompletionResult.Declined
                    result?.verdict == LpVerdict.INFEASIBLE -> MixedPointCompletionResult.Refuted
                    else -> result?.exactPrimal?.take(component.size)?.let { MixedPointCompletionResult.Point(it) }
                        ?: MixedPointCompletionResult.Declined
                }
            }
            if (stop()) return MixedPointCompletionResult.Declined
            when (completed) {
                is MixedPointCompletionResult.Point -> completed.values
                MixedPointCompletionResult.Refuted -> return MixedPointCompletionResult.Refuted
                MixedPointCompletionResult.Declined -> null
            }
        }
        if (values == null || values.size != component.size || stop()) {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_NO_POINT")
            return MixedPointCompletionResult.Declined
        }
        if (compacted) {
            stop.charge(localRows.sumOf { it.columns.size.toLong() + 1L })
            if (stop() || !MixedPointArithmetic(stop).satisfies(values, localRows)) {
                return MixedPointCompletionResult.Declined
            }
        }
        for (index in component.indices) point[component[index]] = values[index]
    }
    if (stop()) return MixedPointCompletionResult.Declined
    onStage?.invoke("RECOVERY_REAL_COMPONENTS_POINT")
    return if (stop()) MixedPointCompletionResult.Declined else MixedPointCompletionResult.Point(point)
}

internal fun compactMixedRealRows(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    originCells: Long = 0L,
): List<ExactRationalInequality>? {
    if (columns <= 0 || columns.toLong() * columns > 65536L || rows.size > 65536 ||
        originCells !in 0L..65536L
    ) return null
    var terms = 0L
    for (row in rows) {
        stop.charge(1L)
        terms += row.columns.size
        if (stop() || terms + rows.size > 65536L) return null
    }
    // Covers live component views, survivor references and bounded rational scratch.
    val cells = 96L * rows.size + 4L * terms + 16L * columns + 8192L + originCells
    if (cells > 65536L) return null
    stop.charge(4L * rows.size + 2L * columns + 1L)
    if (stop()) return null
    val retained = ArrayList<ExactRationalInequality>()
    val arithmetic = MixedPointArithmetic(stop)
    var visits = 0L
    for (row in rows) {
        stop.charge(2L * columns + 8L * row.columns.size + 1L)
        if (stop()) return null
        if (!admittedRealRow(columns, row, arithmetic)) return null
        var matched = false
        for (index in retained.indices) {
            if (++visits > 65536L) return null
            when (compareParallelRows(row, retained[index], arithmetic, stop)) {
                ParallelRowMatch.DIFFERENT -> Unit
                ParallelRowMatch.DECLINED -> return null
                ParallelRowMatch.RETAIN -> matched = true
                ParallelRowMatch.REPLACE -> {
                    retained[index] = row
                    matched = true
                }
            }
            if (matched) break
        }
        if (!matched) retained += row
    }
    stop.charge(retained.size.toLong() + 1L)
    if (stop()) return null
    return retained.toList().takeUnless { stop() }
}

private fun admittedRealRow(
    columns: Int,
    row: ExactRationalInequality,
    arithmetic: MixedPointArithmetic,
): Boolean {
    if (row.columns.isEmpty() || row.columns.size != row.coefficients.size || !arithmetic.admitted(row.rhs)) {
        return false
    }
    for (index in row.columns.indices) {
        val column = row.columns[index]
        if (column !in 0 until columns || (index > 0 && row.columns[index - 1] >= column) ||
            row.coefficients[index].isZero || !arithmetic.admitted(row.coefficients[index])
        ) return false
    }
    return true
}

private enum class ParallelRowMatch { DIFFERENT, DECLINED, RETAIN, REPLACE }

private fun compareParallelRows(
    candidate: ExactRationalInequality,
    previous: ExactRationalInequality,
    arithmetic: MixedPointArithmetic,
    stop: Cancellation,
): ParallelRowMatch {
    stop.charge(2L * candidate.columns.size + 1L)
    if (stop()) return ParallelRowMatch.DECLINED
    if (candidate.columns.size != previous.columns.size) return ParallelRowMatch.DIFFERENT
    val row = candidate
    val kept = previous
    for (index in candidate.columns.indices) {
        if (row.columns[index] != kept.columns[index]) {
            return ParallelRowMatch.DIFFERENT
        }
    }
    val scale = arithmetic.quotient(row.coefficients[0], kept.coefficients[0])
        ?: return ParallelRowMatch.DECLINED
    if (scale <= BigFraction.ZERO) return ParallelRowMatch.DIFFERENT
    for (index in 1 until candidate.columns.size) {
        val difference = arithmetic.subtractProduct(row.coefficients[index], scale,
            kept.coefficients[index]) ?: return ParallelRowMatch.DECLINED
        if (!difference.isZero) return ParallelRowMatch.DIFFERENT
    }
    val difference = arithmetic.subtractProduct(row.rhs, scale, kept.rhs) ?: return ParallelRowMatch.DECLINED
    return if (difference < BigFraction.ZERO || (difference.isZero && row.strict && !kept.strict)) {
        ParallelRowMatch.REPLACE
    } else {
        ParallelRowMatch.RETAIN
    }
}
