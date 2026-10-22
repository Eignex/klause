package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntDisjointSet
import com.eignex.klause.util.bigIntOf
import kotlin.math.round

internal fun completeMixedPoint(
    realColumns: Int,
    integerColumns: Int,
    primal: DoubleArray,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    onSourceInfeasible: ((IntArray) -> Unit)? = null,
    realCompletion: MixedRealCompletion? = null,
    recoveryGuesses: Sequence<List<BigFraction>> = emptySequence(),
): List<BigFraction>? {
    onStage?.invoke("RECOVERY_ATTEMPT")
    fun declined(stage: String): List<BigFraction>? {
        onStage?.invoke(stage)
        return null
    }
    if (realColumns !in 1..10000 || integerColumns !in 0..100000 || rows.size > 100000) {
        return declined("RECOVERY_INPUT")
    }
    if (stop()) return declined("RECOVERY_STOPPED")
    val columns = realColumns + integerColumns
    if (columns > 100000 || primal.size < columns) return declined("RECOVERY_INPUT")
    // Rounded coordinates are trial values; they can authorize only a fully checked witness.
    val integers = ArrayList<BigFraction>(integerColumns)
    for (integer in 0 until integerColumns) {
        stop.charge(1L)
        if (stop()) return declined("RECOVERY_STOPPED")
        integers += BigFraction.ofDouble(round(primal[realColumns + integer]))
            ?: return declined("RECOVERY_INTEGER_INPUT")
    }
    val rowWork = rows.sumOf { it.columns.size.toLong() + 1L }
    for (row in rows) {
        stop.charge(row.columns.size.toLong() + 1L)
        if (stop()) return declined("RECOVERY_STOPPED")
        if (row.columns.any { it !in 0 until columns } ||
            !row.coefficients.admittedSourcePoint() || !listOf(row.rhs).admittedSourcePoint()
        ) return declined("RECOVERY_SOURCE_INPUT")
    }
    val arithmetic = MixedPointArithmetic(stop)
    var realGuess: List<BigFraction>? = null
    for (reconstruct in listOf(false, true)) {
        val reals = ArrayList<BigFraction>(realColumns)
        for (real in 0 until realColumns) {
            stop.charge(1L)
            if (stop()) return declined("RECOVERY_STOPPED")
            val value = if (reconstruct) {
                reconstructRational(primal[real], onStep = { stop.charge(1L) })?.let {
                    BigFraction.of(bigIntOf(it.numerator), bigIntOf(it.denominator))
                }
            } else {
                BigFraction.ofDouble(primal[real])
            }
            if (value == null) break
            reals += value
        }
        if (reals.size != realColumns) continue
        realGuess = reals
        val point = reals + integers
        stop.charge(rowWork)
        if (stop()) return declined("RECOVERY_STOPPED")
        if (arithmetic.satisfies(point, rows) && !stop()) {
            onStage?.invoke(if (reconstruct) "RECOVERY_RECONSTRUCTED_POINT" else "RECOVERY_IEEE_POINT")
            return point
        }
    }
    val residual = ArrayList<ExactRationalInequality>()
    val residualSources = if (integerColumns == 0 && onSourceInfeasible != null) ArrayList<Int>() else null
    for (rowIndex in rows.indices) {
        val row = rows[rowIndex]
        stop.charge(row.columns.size.toLong() + 1L)
        if (stop()) return declined("RECOVERY_STOPPED")
        var bound = row.rhs
        val real = ArrayList<Int>()
        val coefficients = ArrayList<BigFraction>()
        for (index in row.columns.indices) {
            val column = row.columns[index]
            if (column < realColumns) {
                real += column
                coefficients += row.coefficients[index]
            } else {
                bound = arithmetic.subtractProduct(bound, row.coefficients[index], integers[column - realColumns])
                    ?: return declined(if (stop()) "RECOVERY_STOPPED" else "RECOVERY_RATIONAL_RANGE")
            }
        }
        if (real.isEmpty()) {
            if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                return declined("RECOVERY_INTEGER_ROWS")
            }
        } else {
            residual += ExactRationalInequality(real.toIntArray(), coefficients, bound, row.strict)
            residualSources?.add(rowIndex)
        }
    }
    if (residual.any { it.columns.size > 2 }) {
        onStage?.invoke("RECOVERY_EQUALITY_REPAIR_ATTEMPT")
        val reportSourceInfeasible: ((IntArray) -> Unit)? = if (residualSources == null) null else { support ->
            stop.charge(support.size.toLong() + 1L)
            if (!stop() && support.isNotEmpty() && support.all { it in residualSources.indices }) {
                onSourceInfeasible?.invoke(IntArray(support.size) { residualSources[support[it]] })
            }
        }
        if (integerColumns == 0 && !admitsMixedEqualityRepair(realColumns)) {
            val point = completeIndependentRealBlocks(realColumns, primal, realGuess, residual, stop,
                onStage, reportSourceInfeasible, realCompletion)
            stop.charge(rowWork)
            if (stop()) return declined("RECOVERY_STOPPED")
            if (point != null && arithmetic.satisfies(point, rows) && !stop()) {
                onStage?.invoke("RECOVERY_SOURCE_COMPONENTS_POINT")
                return point
            }
            return declined("RECOVERY_NON_DIFFERENCE")
        }
        val repair = prepareMixedEqualityRepair(realColumns, residual, stop, onStage,
            trackSourceRows = residualSources != null)
        var repaired = realGuess?.let { repair?.point(it) }
        if (stop()) return declined("RECOVERY_STOPPED")
        for (difference in listOf(false, true)) {
            // Repair attempts share one temporary point slot.
            if (difference) repaired = null
            if (difference && repair != null && integerColumns == 0) {
                var guessEntries = 0L
                for (guess in recoveryGuesses) {
                    stop.charge(1L)
                    if (stop()) return declined("RECOVERY_STOPPED")
                    guessEntries += guess.size.toLong() + 1L
                    if (guessEntries > 65536L) break
                    if (guess.size != realColumns) continue
                    val retained = repair.point(guess)
                    stop.charge(rowWork)
                    if (stop()) return declined("RECOVERY_STOPPED")
                    if (retained != null && arithmetic.satisfies(retained, rows) && !stop()) {
                        onStage?.invoke("RECOVERY_RETAINED_EQUALITY_POINT")
                        return retained.takeUnless { stop() }
                    }
                }
            }
            val reals = if (difference) repair?.completePoint(residual, realCompletion) else repaired
            if (stop()) return declined("RECOVERY_STOPPED")
            // Guessed integer coordinates cannot supply a refutation of the source model.
            val support = repair?.lastResidualInfeasibleRows
            if (support != null) reportSourceInfeasible?.invoke(support)
            if (stop()) return declined("RECOVERY_STOPPED")
            if (reals == null) continue
            val point = reals + integers
            stop.charge(rowWork)
            if (!stop() && arithmetic.satisfies(point, rows) && !stop()) {
                onStage?.invoke(if (difference) "RECOVERY_EQUALITY_RESIDUAL_POINT" else "RECOVERY_EQUALITY_POINT")
                return point
            }
            onStage?.invoke("RECOVERY_EQUALITY_REJECTED")
        }
        if (stop()) return declined("RECOVERY_STOPPED")
        return declined("RECOVERY_NON_DIFFERENCE")
    }
    val reals = if (residual.isEmpty()) {
        List(realColumns) { BigFraction.ZERO }
    } else {
        val graph = RealDifferenceSystem.prepareRows(realColumns, residual, stop)
            ?: return declined("RECOVERY_GRAPH_UNAVAILABLE")
        val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
        if (stop()) return declined("RECOVERY_STOPPED")
        (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return declined("RECOVERY_GRAPH_NO_POINT")
    }
    val point = reals + integers
    stop.charge(rowWork)
    val valid = !stop() && arithmetic.satisfies(point, rows) && !stop()
    onStage?.invoke(if (valid) "RECOVERY_GRAPH_POINT" else "RECOVERY_GRAPH_NO_POINT")
    return point.takeIf { valid }
}

private fun completeIndependentRealBlocks(
    columns: Int,
    primal: DoubleArray,
    guess: List<BigFraction>?,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
    onInfeasible: ((IntArray) -> Unit)?,
    realCompletion: MixedRealCompletion?,
): List<BigFraction>? {
    if (rows.sumOf { it.columns.size.toLong() + 1L } > 65536L) return null
    // Path compression bounds the union walks by this batch plus the per-row visits.
    stop.charge(8L * columns * columns + columns)
    if (stop()) return null
    val groups = IntDisjointSet(columns)
    for (row in rows) {
        stop.charge(8L * (row.columns.size + 1L))
        if (stop()) return null
        val first = row.columns.firstOrNull() ?: continue
        for (column in row.columns) groups.union(first, column)
    }
    stop.charge(4L * columns + 4L * rows.size)
    if (stop()) return null
    val members = groups.groups()
    val byRoot = rows.indices.groupBy { groups.find(rows[it].columns.first()) }
    var dimensions = 0L
    for (component in members) {
        val selected = byRoot[groups.find(component.first())] ?: continue
        dimensions += component.size.toLong() * component.size
        onStage?.invoke("RECOVERY_SOURCE_COMPONENT_SHAPE_${component.size}_${selected.size}")
        if (dimensions > 65536L) {
            onStage?.invoke("RECOVERY_SOURCE_COMPONENT_DIMENSION")
            return null
        }
    }
    stop.charge(3L * columns)
    if (stop()) return null
    val point = guess?.toMutableList() ?: MutableList(columns) { BigFraction.ZERO }
    val local = IntArray(columns) { -1 }
    for (component in members) {
        val selected = byRoot[groups.find(component.first())] ?: continue
        stop.charge(3L * component.size + selected.size)
        if (stop()) return null
        for (index in component.indices) local[component[index]] = index
        val localRows = ArrayList<ExactRationalInequality>(selected.size)
        for (rowIndex in selected) {
            val row = rows[rowIndex]
            stop.charge(row.columns.size.toLong() + 1L)
            if (stop()) return null
            localRows += ExactRationalInequality(IntArray(row.columns.size) { local[row.columns[it]] },
                row.coefficients, row.rhs, row.strict)
        }
        val reportInfeasible: ((IntArray) -> Unit)? = if (onInfeasible == null) null else { support ->
            stop.charge(support.size.toLong() + 1L)
            if (!stop() && support.isNotEmpty() && support.all { it in selected.indices }) {
                val original = IntArray(support.size) { selected[support[it]] }
                onStage?.invoke("RECOVERY_SOURCE_COMPONENT_CONFLICT")
                if (!stop()) onInfeasible(original)
            }
        }
        val values = completeMixedPoint(component.size, 0, DoubleArray(component.size) { primal[component[it]] },
            localRows, stop, onStage, reportInfeasible, realCompletion) ?: return null
        if (stop()) return null
        for (index in component.indices) point[component[index]] = values[index]
    }
    return point.takeUnless { stop() }
}
