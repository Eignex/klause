package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.math.round

internal fun completeMixedPoint(
    realColumns: Int,
    integerColumns: Int,
    primal: DoubleArray,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): List<BigFraction>? {
    onStage?.invoke("RECOVERY_ATTEMPT")
    fun declined(stage: String): List<BigFraction>? {
        onStage?.invoke(stage)
        return null
    }
    if (realColumns !in 1..10000 || integerColumns !in 1..100000 || rows.size > 100000) {
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
        if (point.satisfiesSourceRows(rows, stop) && !stop()) {
            onStage?.invoke(if (reconstruct) "RECOVERY_RECONSTRUCTED_POINT" else "RECOVERY_IEEE_POINT")
            return point
        }
    }
    val residual = ArrayList<ExactRationalInequality>()
    for (row in rows) {
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
                bound -= row.coefficients[index] * integers[column - realColumns]
                if (!listOf(bound).admittedSourcePoint()) return declined("RECOVERY_RATIONAL_RANGE")
            }
        }
        if (real.isEmpty()) {
            if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                return declined("RECOVERY_INTEGER_ROWS")
            }
        } else {
            residual += ExactRationalInequality(real.toIntArray(), coefficients, bound, row.strict)
        }
    }
    if (residual.any { it.columns.size > 2 }) {
        onStage?.invoke("RECOVERY_EQUALITY_REPAIR_ATTEMPT")
        val repair = prepareMixedEqualityRepair(realColumns, residual, stop, onStage)
        val repaired = realGuess?.let { repair?.point(it) }
        if (stop()) return declined("RECOVERY_STOPPED")
        for (difference in listOf(false, true)) {
            val reals = if (difference) repair?.differencePoint(residual) else repaired
            if (stop()) return declined("RECOVERY_STOPPED")
            if (reals == null) continue
            val point = reals + integers
            stop.charge(rowWork)
            if (!stop() && point.satisfiesSourceRows(rows, stop) && !stop()) {
                onStage?.invoke(if (difference) "RECOVERY_EQUALITY_GRAPH_POINT" else "RECOVERY_EQUALITY_POINT")
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
    val valid = !stop() && point.satisfiesSourceRows(rows, stop) && !stop()
    onStage?.invoke(if (valid) "RECOVERY_GRAPH_POINT" else "RECOVERY_GRAPH_NO_POINT")
    return point.takeIf { valid }
}
