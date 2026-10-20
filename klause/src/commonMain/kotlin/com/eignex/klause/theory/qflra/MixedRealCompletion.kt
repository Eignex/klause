package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
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
    onSupport: ((IntArray) -> Unit)? = null,
    cache: RealResidualRefutationCache? = null,
): Boolean {
    if (budget.context.certificationPolicy !== ProductionLpCertificationPolicy) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_ATTEMPT")
    val result = solveRealComponents(columns, rows, budget, stop, onStage, negativeOnly = true, cache = cache)
    if (stop() || result !is RealComponents.Refuted) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_REFUTED")
    stop.charge(result.rows.size.toLong() + 1L)
    if (stop()) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_SUPPORT_R_${result.rows.size}_FROM_${rows.size}")
    if (!stop()) onSupport?.invoke(result.rows)
    return !stop()
}

private sealed interface RealComponents {
    class Point(val values: List<BigFraction>) : RealComponents
    class Refuted(val rows: IntArray) : RealComponents
    data object Declined : RealComponents
}

private fun solveRealComponents(
    columns: Int,
    rows: List<ExactRationalInequality>,
    budget: SourceLpBudget,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
    negativeOnly: Boolean = false,
    cache: RealResidualRefutationCache? = null,
): RealComponents {
    if (columns < 0 || columns.toLong() * columns > 65536L || rows.size > 65536) return RealComponents.Declined
    stop.charge(4L * columns + rows.size + 1L)
    if (stop()) return RealComponents.Declined
    val work = MixedPointArithmetic(stop)
    val used = BooleanArray(columns)
    val normalized = ArrayList<ExactRationalInequality>(rows.size)
    val origins = IntArrayList()
    var entries = 0L
    var falseConstant = -1
    for ((rowIndex, row) in rows.withIndex()) {
        entries += row.columns.size + 1L
        stop.charge(8L * (row.columns.size + 1L))
        if (entries > 65536L || stop() || row.columns.size != row.coefficients.size ||
            row.columns.any { it !in 0 until columns } ||
            row.columns.indices.any { it > 0 && row.columns[it - 1] >= row.columns[it] } ||
            !work.admitted(row.rhs) || !row.coefficients.all(work::admitted)
        ) return RealComponents.Declined
        val nonzero = row.columns.indices.filter { !row.coefficients[it].isZero }
        if (nonzero.isEmpty()) {
            if (if (row.strict) BigFraction.ZERO >= row.rhs else BigFraction.ZERO > row.rhs) falseConstant = rowIndex
        } else {
            val selected = nonzero.map { row.columns[it] }.toIntArray()
            for (column in selected) used[column] = true
            normalized += ExactRationalInequality(selected, nonzero.map { row.coefficients[it] }, row.rhs, row.strict)
            origins.add(rowIndex)
        }
    }
    if (stop()) return RealComponents.Declined
    if (falseConstant >= 0) return RealComponents.Refuted(intArrayOf(falseConstant))
    val cached = cache?.find(normalized, stop)
    if (stop()) return RealComponents.Declined
    if (cached != null) {
        onStage?.invoke("PURE_REAL_EQUALITY_GENERAL_MEMO_HIT")
        return if (stop()) RealComponents.Declined else
            RealComponents.Refuted(IntArray(cached.size) { origins[cached[it]] })
    }
    val original = used.indices.filter { used[it] }
    stop.charge(4L * columns + 4L * entries)
    if (stop()) return RealComponents.Declined
    val local = IntArray(columns) { -1 }
    for (index in original.indices) local[original[index]] = index
    val compact = normalized.map { row ->
        ExactRationalInequality(row.columns.map { local[it] }.toIntArray(), row.coefficients, row.rhs, row.strict)
    }
    val result = solveCompactRealComponents(original.size, compact, budget, stop, onStage, negativeOnly)
    if (!stop() && result is RealComponents.Refuted) {
        cache?.remember(normalized, result.rows, stop)
        stop.charge(result.rows.size.toLong() + 1L)
        if (stop()) return RealComponents.Declined
        return RealComponents.Refuted(IntArray(result.rows.size) { origins[result.rows[it]] })
    }
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
    negativeOnly: Boolean,
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
    val byRoot = HashMap<Int, MutableList<Int>>()
    for ((rowIndex, row) in rows.withIndex()) {
        stop.charge(4L)
        if (stop()) return RealComponents.Declined
        val first = row.columns.firstOrNull() ?: continue
        byRoot.getOrPut(groups.find(first)) { ArrayList() } += rowIndex
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
        for (rowIndex in selected) {
            val row = rows[rowIndex]
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
                is RealDifferenceSystem.Result.Infeasible -> {
                    val support = result.rowSupport ?: IntArray(localRows.size) { it }
                    stop.charge(support.size.toLong() + 1L)
                    if (stop()) return RealComponents.Declined
                    return RealComponents.Refuted(IntArray(support.size) { selected[support[it]] })
                }
                else -> null
            }
        } else {
            onStage?.invoke("RECOVERY_REAL_COMPONENT_SHAPE_${component.size}_${localRows.size}")
            if (negativeOnly) {
                onStage?.invoke("PURE_REAL_EQUALITY_SUBSET_GRAPH_ATTEMPT")
                var support: IntArray? = null
                val refuted = RealDifferenceSystem.refutesSubset(component.size, localRows, stop) { support = it }
                if (stop()) return RealComponents.Declined
                if (refuted) {
                    onStage?.invoke("PURE_REAL_EQUALITY_SUBSET_GRAPH_REFUTED")
                    val indices = support ?: IntArray(localRows.size) { it }
                    stop.charge(indices.size.toLong() + 1L)
                    return if (stop()) RealComponents.Declined else
                        RealComponents.Refuted(IntArray(indices.size) { selected[indices[it]] })
                }
            }
            val admitted = if (budget.admitsDimensions(component.size, localRows.size.toLong())) {
                CompactRealRows(localRows, IntArray(localRows.size) { it })
            } else {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACTION_ATTEMPT")
                compactRealRows(localRows, stop)?.also {
                    onStage?.invoke("RECOVERY_REAL_COMPONENT_COMPACT_${localRows.size}_TO_${it.rows.size}")
                } ?: return RealComponents.Declined
            }
            val admittedRows = admitted.rows
            if (stop()) return RealComponents.Declined
            if (!budget.admitsDimensions(component.size, admittedRows.size.toLong())) {
                onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_DIMENSION")
                return RealComponents.Declined
            }
            onStage?.invoke("RECOVERY_REAL_COMPONENT_LP_REQUEST")
            val result = SourceLp(admittedRows, component.size, budget).use { owner -> owner.solve(stop) }
            if (stop()) return RealComponents.Declined
            if (result?.verdict == LpVerdict.INFEASIBLE &&
                budget.context.certificationPolicy === ProductionLpCertificationPolicy
            ) {
                val support = result.sourceRowSupport(component.size, admittedRows.size, stop)
                    ?: IntArray(admittedRows.size) { it }
                stop.charge(4L * support.size + 1L)
                if (stop()) return RealComponents.Declined
                return RealComponents.Refuted(IntArray(support.size) { selected[admitted.parents[support[it]]] })
            }
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

private class CompactRealRows(val rows: List<ExactRationalInequality>, val parents: IntArray)

private fun compactRealRows(
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
): CompactRealRows? {
    val work = MixedPointArithmetic(stop)
    val directions = LinkedHashMap<List<Pair<Int, BigFraction>>, ExactRationalInequality>()
    val parents = HashMap<List<Pair<Int, BigFraction>>, Int>()
    for ((rowIndex, row) in rows.withIndex()) {
        stop.charge(8L * row.columns.size + 4L)
        if (stop()) return null
        val first = row.coefficients.firstOrNull()?.takeUnless { it.isZero } ?: return null
        val scale = if (first.signum() < 0) first.negated() else first
        val coefficients = row.coefficients.map { work.quotient(it, scale) ?: return null }
        val rhs = work.quotient(row.rhs, scale) ?: return null
        val key = row.columns.indices.map { row.columns[it] to coefficients[it] }
        val previous = directions[key]
        val stronger = previous == null ||
            (work.subtractProduct(rhs, BigFraction.ONE, previous.rhs) ?: return null).let {
                it.signum() < 0 || (it.isZero && row.strict && !previous.strict)
            }
        if (stronger) {
            directions[key] = ExactRationalInequality(row.columns, coefficients, rhs, row.strict)
            parents[key] = rowIndex
        }
    }
    stop.charge(directions.size.toLong() + 1L)
    return if (stop()) null else CompactRealRows(directions.values.toList(),
        directions.keys.map { parents.getValue(it) }.toIntArray())
}

private fun CertifiedLpResult.sourceRowSupport(variables: Int, rowCount: Int, stop: Cancellation): IntArray? {
    val conflict = rationalConflict
    stop.charge(4L * (rowCount + (conflict?.rows?.size ?: 0) + (conflict?.bounds?.size ?: 0)) + 1L)
    if (stop()) return null
    val rows = conflict?.rows ?: farkasRay?.let { ray ->
        if (ray.size != rowCount) return null
        ray.indices.filter { ray[it] != 0L }.toIntArray()
    } ?: return null
    if (rows.any { it !in 0 until rowCount }) return null
    val selected = rows.toMutableSet()
    for (bound in conflict?.bounds.orEmpty()) {
        // SourceLp leaves source columns free; each bounded slack belongs to its original input row.
        val row = bound.column - variables
        if (row !in 0 until rowCount) return null
        selected += row
    }
    return if (selected.isEmpty() || stop()) null else selected.sorted().toIntArray()
}
