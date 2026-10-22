package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal fun prepareMixedEqualityRepair(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    trackSourceRows: Boolean = false,
): MixedEqualityRepair? {
    if (!admitsMixedEqualityRepair(columns)) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return null
    }
    if (stop()) return null
    val work = MixedPointArithmetic(stop)
    val directions = HashMap<MixedEqualityKey, IntArray>()
    val equalities = ArrayList<MixedEqualityKey>()
    var entries = 0L
    for (rowIndex in rows.indices) {
        val row = rows[rowIndex]
        stop.charge(4L * row.columns.size + 3L)
        entries += row.columns.size + 1L
        if (entries > MAX_REPAIR_ENTRIES) {
            onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
            return null
        }
        if (stop()) return null
        if (row.columns.any { it !in 0 until columns }) return null
        if (!row.coefficients.all(work::admitted) || !work.admitted(row.rhs)) return null
        if (row.strict) continue
        val first = row.coefficients.firstOrNull { !it.isZero } ?: continue
        val reversed = first < BigFraction.ZERO
        val terms = row.columns.indices.filter { !row.coefficients[it].isZero }.map {
            row.columns[it] to if (reversed) row.coefficients[it].negated() else row.coefficients[it]
        }
        val key = MixedEqualityKey(terms, if (reversed) row.rhs.negated() else row.rhs)
        val selected = directions.getOrPut(key) { intArrayOf(-1, -1) }
        val paired = selected.all { it >= 0 }
        val direction = if (reversed) 1 else 0
        if (selected[direction] < 0) selected[direction] = rowIndex
        if (!paired && selected.all { it >= 0 }) equalities += key
    }
    if (equalities.isEmpty()) {
        onStage?.invoke("RECOVERY_EQUALITY_NO_PAIRS")
        return null
    }
    onStage?.invoke("RECOVERY_EQUALITY_PAIRED")
    stop.charge(3L * columns)
    if (stop()) return null
    val pivots = arrayOfNulls<ExactRationalInequality>(columns)
    val pivotSources = if (trackSourceRows) arrayOfNulls<IntArray>(columns) else null
    var sourceEntries = 0L
    for (equality in equalities) {
        stop.charge(3L * equality.terms.size + 1L)
        if (stop()) return null
        val coefficients = equality.terms.toMap().toMutableMap()
        val sources = if (trackSourceRows) directions.getValue(equality).toMutableSet() else null
        var bound = equality.bound
        while (coefficients.isNotEmpty()) {
            stop.charge(coefficients.size.toLong() + 1L)
            if (stop()) return null
            val pivot = coefficients.keys.minOrNull() ?: return null
            if (pivot !in pivots.indices) return null
            val previous = pivots[pivot]
            if (previous == null) {
                stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
                if (stop()) return null
                val ordered = coefficients.keys.sorted()
                pivots[pivot] = ExactRationalInequality(ordered.toIntArray(),
                    ordered.map(coefficients::getValue), bound)
                if (sources != null) {
                    stop.charge(sources.size.toLong())
                    if (stop()) return null
                    if (entries + sourceEntries + sources.size > MAX_REPAIR_ENTRIES) {
                        onStage?.invoke("RECOVERY_EQUALITY_SUPPORT_LIMIT")
                        return null
                    }
                    requireNotNull(pivotSources)[pivot] = sources.toIntArray()
                    sourceEntries += sources.size
                }
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.first())
                ?: return null
            if (sources != null) {
                val inherited = pivotSources?.get(pivot) ?: return null
                stop.charge(inherited.size.toLong() + 1L)
                if (stop()) return null
                for (source in inherited) sources += source
                if (entries + sourceEntries + sources.size > MAX_REPAIR_ENTRIES) {
                    onStage?.invoke("RECOVERY_EQUALITY_SUPPORT_LIMIT")
                    return null
                }
            }
            coefficients.remove(pivot)
            bound = work.subtractProduct(bound, scale, previous.rhs) ?: return null
            for (index in 1 until previous.columns.size) {
                val column = previous.columns[index]
                val coefficient = previous.coefficients[index]
                val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO, scale, coefficient)
                    ?: return null
                if (value.isZero) coefficients.remove(column) else coefficients[column] = value
            }
        }
        if (coefficients.isEmpty() && !bound.isZero) return null
    }
    return MixedEqualityRepair(pivots, stop, onStage, pivotSources, MAX_REPAIR_ENTRIES - entries - sourceEntries)
}

internal class MixedEqualityRepair(
    private val pivots: Array<ExactRationalInequality?>,
    private val stop: Cancellation,
    private val onStage: ((String) -> Unit)?,
    private val pivotSources: Array<IntArray?>?,
    private val sourceSupportAllowance: Long,
) {
    private val work = MixedPointArithmetic(stop)
    var lastResidualInfeasibleRows: IntArray? = null
        private set

    fun point(guess: List<BigFraction>): List<BigFraction>? {
        stop.charge(2L * guess.size)
        if (guess.size != pivots.size || stop() || !guess.all(work::admitted)) return null
        val point = guess.toMutableList()
        for (pivot in pivots.indices.reversed()) {
            stop.charge(1L)
            if (stop()) return null
            val equation = pivots[pivot] ?: continue
            var value = equation.rhs
            for (index in 1 until equation.columns.size) {
                value = work.subtractProduct(value, equation.coefficients[index], point[equation.columns[index]])
                    ?: return null
            }
            point[pivot] = work.quotient(value, equation.coefficients.first()) ?: return null
        }
        return point.takeUnless { stop() }?.also { onStage?.invoke("RECOVERY_EQUALITY_RECONSTRUCTED") }
    }

    fun completePoint(
        rows: List<ExactRationalInequality>,
        realCompletion: ((Int, List<ExactRationalInequality>, Cancellation) -> List<BigFraction>?)?,
    ): List<BigFraction>? {
        lastResidualInfeasibleRows = null
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val sources = pivotSources?.let { linkedSetOf(rowIndex) }
            if (sources != null && entries + sources.size > sourceSupportAllowance) {
                onStage?.invoke("RECOVERY_EQUALITY_SUPPORT_LIMIT")
                return null
            }
            stop.charge(3L * row.columns.size + pivots.size + 1L)
            if (stop()) return null
            val coefficients = row.columns.indices.associate { row.columns[it] to row.coefficients[it] }.toMutableMap()
            var bound = row.rhs
            for (pivot in pivots.indices) {
                val coefficient = coefficients[pivot] ?: continue
                val equation = pivots[pivot] ?: continue
                val scale = work.quotient(coefficient, equation.coefficients.first()) ?: return null
                if (sources != null) {
                    val inherited = pivotSources?.get(pivot) ?: return null
                    stop.charge(inherited.size.toLong() + 1L)
                    if (stop()) return null
                    for (source in inherited) sources += source
                    if (entries + sources.size > sourceSupportAllowance) {
                        onStage?.invoke("RECOVERY_EQUALITY_SUPPORT_LIMIT")
                        return null
                    }
                }
                coefficients.remove(pivot)
                bound = work.subtractProduct(bound, scale, equation.rhs) ?: return null
                for (index in 1 until equation.columns.size) {
                    val column = equation.columns[index]
                    val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO,
                        scale, equation.coefficients[index]) ?: return null
                    if (value.isZero) coefficients.remove(column) else coefficients[column] = value
                }
            }
            coefficients.entries.removeAll { it.value.isZero }
            if (coefficients.isEmpty()) {
                if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                    onStage?.invoke("RECOVERY_EQUALITY_RESIDUAL_FALSE")
                    if (sources != null) {
                        stop.charge(sources.size.toLong())
                        if (!stop()) lastResidualInfeasibleRows = sources.toIntArray()
                    }
                    return null
                }
                continue
            }
            entries += coefficients.size + 1L
            if (entries > MAX_REPAIR_ENTRIES) {
                onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
                return null
            }
            if (sources != null && entries + sources.size > sourceSupportAllowance) {
                onStage?.invoke("RECOVERY_EQUALITY_SUPPORT_LIMIT")
                return null
            }
            stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
            if (stop()) return null
            val ordered = coefficients.keys.sorted()
            residual += ExactRationalInequality(ordered.toIntArray(), ordered.map(coefficients::getValue), bound,
                row.strict)
        }
        val graph = RealDifferenceSystem.prepareRows(pivots.size, residual, stop)
        if (stop()) return null
        val free = if (graph == null) {
            onStage?.invoke("RECOVERY_EQUALITY_GRAPH_UNAVAILABLE")
            realCompletion?.invoke(pivots.size, residual, stop) ?: return null
        } else {
            onStage?.invoke("RECOVERY_EQUALITY_GRAPH_ATTEMPT")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return null
            (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return null
        }
        onStage?.invoke("RECOVERY_EQUALITY_FREE_POINT")
        return point(free)
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)

internal fun admitsMixedEqualityRepair(columns: Int): Boolean =
    columns > 0 && columns.toLong() * columns <= MAX_REPAIR_ENTRIES


private const val MAX_REPAIR_ENTRIES = 65536L
