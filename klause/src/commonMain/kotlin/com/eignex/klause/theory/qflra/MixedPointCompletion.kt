package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.engine.reconstructRational
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
    realCompletion: ((Int, List<ExactRationalInequality>, Cancellation) -> List<BigFraction>?)? = null,
): List<BigFraction>? = (checkMixedPoint(realColumns, integerColumns, primal, rows, stop, onStage = onStage,
    realCompletion = realCompletion?.let { complete ->
        { columns, residual, token ->
            complete(columns, residual, token)?.let { MixedPointCompletionResult.Point(it) }
                ?: MixedPointCompletionResult.Declined
        }
    }) as? MixedPointCompletionResult.Point)?.values

internal fun checkMixedPoint(
    realColumns: Int,
    integerColumns: Int,
    primal: DoubleArray,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    realCompletion: ((Int, List<ExactRationalInequality>, Cancellation) -> MixedPointCompletionResult)? = null,
): MixedPointCompletionResult {
    onStage?.invoke("RECOVERY_ATTEMPT")
    fun declined(stage: String): MixedPointCompletionResult {
        onStage?.invoke(stage)
        return MixedPointCompletionResult.Declined
    }
    fun refuted(stage: String): MixedPointCompletionResult {
        onStage?.invoke(stage)
        return if (integerColumns == 0 && !stop()) MixedPointCompletionResult.Refuted
            else MixedPointCompletionResult.Declined
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
            return MixedPointCompletionResult.Point(point)
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
                bound = arithmetic.subtractProduct(bound, row.coefficients[index], integers[column - realColumns])
                    ?: return declined(if (stop()) "RECOVERY_STOPPED" else "RECOVERY_RATIONAL_RANGE")
            }
        }
        if (real.isEmpty()) {
            if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                return refuted("RECOVERY_INTEGER_ROWS")
            }
        } else {
            residual += ExactRationalInequality(real.toIntArray(), coefficients, bound, row.strict)
        }
    }
    if (residual.any { it.columns.size > 2 }) {
        onStage?.invoke("RECOVERY_EQUALITY_REPAIR_ATTEMPT")
        val prepared = prepareSourceEqualities(realColumns, residual, stop, onStage)
        if (stop()) return declined("RECOVERY_STOPPED")
        if (prepared === SourceEqualityPreparation.Refuted) return refuted("RECOVERY_EQUALITY_REFUTED")
        val repair = (prepared as? SourceEqualityPreparation.Ready)?.repair
        val repaired = realGuess?.let { repair?.point(it) }
        if (stop()) return declined("RECOVERY_STOPPED")
        for (difference in listOf(false, true)) {
            val completed = if (difference) repair?.completeResult(residual, realCompletion) else
                repaired?.let { MixedPointCompletionResult.Point(it) }
            if (stop()) return declined("RECOVERY_STOPPED")
            if (completed === MixedPointCompletionResult.Refuted) return refuted("RECOVERY_RESIDUAL_REFUTED")
            val reals = (completed as? MixedPointCompletionResult.Point)?.values ?: continue
            val point = reals + integers
            stop.charge(rowWork)
            if (!stop() && arithmetic.satisfies(point, rows) && !stop()) {
                onStage?.invoke(if (difference) "RECOVERY_EQUALITY_RESIDUAL_POINT" else "RECOVERY_EQUALITY_POINT")
                return MixedPointCompletionResult.Point(point)
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
        if (result is RealDifferenceSystem.Result.Infeasible) return refuted("RECOVERY_GRAPH_REFUTED")
        (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return declined("RECOVERY_GRAPH_NO_POINT")
    }
    val point = reals + integers
    stop.charge(rowWork)
    val valid = !stop() && arithmetic.satisfies(point, rows) && !stop()
    onStage?.invoke(if (valid) "RECOVERY_GRAPH_POINT" else "RECOVERY_GRAPH_NO_POINT")
    return if (valid) MixedPointCompletionResult.Point(point) else MixedPointCompletionResult.Declined
}
