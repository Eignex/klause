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
): List<BigFraction>? {
    if (realColumns !in 1..10000 || integerColumns !in 1..100000 || rows.size > 100000 || stop()) return null
    val columns = realColumns + integerColumns
    if (columns > 100000 || primal.size < columns) return null
    // Rounded coordinates are trial values; they can authorize only a fully checked witness.
    val integers = ArrayList<BigFraction>(integerColumns)
    for (integer in 0 until integerColumns) {
        stop.charge(1L)
        if (stop()) return null
        integers += BigFraction.ofDouble(round(primal[realColumns + integer])) ?: return null
    }
    val rowWork = rows.sumOf { it.columns.size.toLong() + 1L }
    for (row in rows) {
        stop.charge(row.columns.size.toLong() + 1L)
        if (stop() || row.columns.any { it !in 0 until columns } ||
            !row.coefficients.admittedSourcePoint() || !listOf(row.rhs).admittedSourcePoint()
        ) return null
    }
    for (reconstruct in listOf(false, true)) {
        val reals = ArrayList<BigFraction>(realColumns)
        for (real in 0 until realColumns) {
            stop.charge(1L)
            if (stop()) return null
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
        val point = reals + integers
        stop.charge(rowWork)
        if (stop()) return null
        if (point.satisfiesSourceRows(rows, stop) && !stop()) return point
    }
    val residual = ArrayList<ExactRationalInequality>()
    for (row in rows) {
        stop.charge(row.columns.size.toLong() + 1L)
        if (stop()) return null
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
                if (!listOf(bound).admittedSourcePoint()) return null
            }
        }
        if (real.isEmpty()) {
            if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) return null
        } else {
            if (real.size > 2) return null
            residual += ExactRationalInequality(real.toIntArray(), coefficients, bound, row.strict)
        }
    }
    val reals = if (residual.isEmpty()) {
        List(realColumns) { BigFraction.ZERO }
    } else {
        val graph = RealDifferenceSystem.prepareRows(realColumns, residual, stop) ?: return null
        val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
        if (stop()) return null
        (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return null
    }
    val point = reals + integers
    stop.charge(rowWork)
    return point.takeIf { !stop() && it.satisfiesSourceRows(rows, stop) && !stop() }
}
