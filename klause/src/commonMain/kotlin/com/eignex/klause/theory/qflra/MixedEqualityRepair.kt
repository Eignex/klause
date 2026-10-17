package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal sealed interface SourceEqualityPreparation {
    class Ready(val repair: MixedEqualityRepair) : SourceEqualityPreparation
    data object Refuted : SourceEqualityPreparation
    data object Declined : SourceEqualityPreparation
}

private sealed interface SourceEqualityResidual {
    class Rows(val rows: List<ExactRationalInequality>) : SourceEqualityResidual
    data object Refuted : SourceEqualityResidual
    data object Declined : SourceEqualityResidual
}

internal fun admitsSourceEqualityColumns(columns: Int): Boolean =
    columns > 0 && columns.toLong() * columns <= MAX_REPAIR_ENTRIES

internal fun prepareMixedEqualityRepair(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): MixedEqualityRepair? {
    val prepared = prepareSourceEqualities(columns, rows, stop, onStage)
    return (prepared as? SourceEqualityPreparation.Ready)?.repair
}

internal fun prepareSourceEqualities(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): SourceEqualityPreparation {
    if (!admitsSourceEqualityColumns(columns)) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return SourceEqualityPreparation.Declined
    }
    if (stop()) return SourceEqualityPreparation.Declined
    val work = MixedPointArithmetic(stop)
    val directions = HashMap<MixedEqualityKey, Int>()
    val equalities = ArrayList<MixedEqualityKey>()
    var entries = 0L
    for (row in rows) {
        stop.charge(4L * row.columns.size + 3L)
        entries += row.columns.size + 1L
        if (entries > MAX_REPAIR_ENTRIES) {
            onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
            return SourceEqualityPreparation.Declined
        }
        if (stop()) return SourceEqualityPreparation.Declined
        if (row.columns.any { it !in 0 until columns }) return SourceEqualityPreparation.Declined
        if (!row.coefficients.all(work::admitted) || !work.admitted(row.rhs)) return SourceEqualityPreparation.Declined
        if (row.strict) continue
        val first = row.coefficients.firstOrNull { !it.isZero } ?: continue
        val reversed = first < BigFraction.ZERO
        val terms = row.columns.indices.filter { !row.coefficients[it].isZero }.map {
            row.columns[it] to if (reversed) row.coefficients[it].negated() else row.coefficients[it]
        }
        val key = MixedEqualityKey(terms, if (reversed) row.rhs.negated() else row.rhs)
        val previous = directions[key] ?: 0
        val next = previous or if (reversed) 2 else 1
        directions[key] = next
        if (next == 3 && previous != 3) equalities += key
    }
    if (equalities.isEmpty()) {
        onStage?.invoke("RECOVERY_EQUALITY_NO_PAIRS")
        return SourceEqualityPreparation.Declined
    }
    onStage?.invoke("RECOVERY_EQUALITY_PAIRED")
    stop.charge(3L * columns)
    if (stop()) return SourceEqualityPreparation.Declined
    val pivots = arrayOfNulls<ExactRationalInequality>(columns)
    for (equality in equalities) {
        stop.charge(3L * equality.terms.size + 1L)
        if (stop()) return SourceEqualityPreparation.Declined
        val coefficients = equality.terms.toMap().toMutableMap()
        var bound = equality.bound
        while (coefficients.isNotEmpty()) {
            stop.charge(coefficients.size.toLong() + 1L)
            if (stop()) return SourceEqualityPreparation.Declined
            val pivot = coefficients.keys.minOrNull() ?: return SourceEqualityPreparation.Declined
            if (pivot !in pivots.indices) return SourceEqualityPreparation.Declined
            val previous = pivots[pivot]
            if (previous == null) {
                stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
                if (stop()) return SourceEqualityPreparation.Declined
                val ordered = coefficients.keys.sorted()
                pivots[pivot] = ExactRationalInequality(ordered.toIntArray(),
                    ordered.map(coefficients::getValue), bound)
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.first())
                ?: return SourceEqualityPreparation.Declined
            coefficients.remove(pivot)
            bound = work.subtractProduct(bound, scale, previous.rhs) ?: return SourceEqualityPreparation.Declined
            for (index in 1 until previous.columns.size) {
                val column = previous.columns[index]
                val coefficient = previous.coefficients[index]
                val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO, scale, coefficient)
                    ?: return SourceEqualityPreparation.Declined
                if (value.isZero) coefficients.remove(column) else coefficients[column] = value
            }
        }
        if (coefficients.isEmpty() && !bound.isZero) {
            return if (stop()) SourceEqualityPreparation.Declined else SourceEqualityPreparation.Refuted
        }
    }
    return SourceEqualityPreparation.Ready(MixedEqualityRepair(pivots, stop, onStage))
}

internal class MixedEqualityRepair(
    private val pivots: Array<ExactRationalInequality?>,
    private val stop: Cancellation,
    private val onStage: ((String) -> Unit)?,
) {
    private val work = MixedPointArithmetic(stop)

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

    private fun reduceRows(rows: List<ExactRationalInequality>): SourceEqualityResidual {
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for (row in rows) {
            stop.charge(3L * row.columns.size + pivots.size + 1L)
            if (stop()) return SourceEqualityResidual.Declined
            val coefficients = row.columns.indices.associate { row.columns[it] to row.coefficients[it] }.toMutableMap()
            var bound = row.rhs
            for (pivot in pivots.indices) {
                val coefficient = coefficients[pivot] ?: continue
                val equation = pivots[pivot] ?: continue
                val scale = work.quotient(coefficient, equation.coefficients.first())
                    ?: return SourceEqualityResidual.Declined
                coefficients.remove(pivot)
                bound = work.subtractProduct(bound, scale, equation.rhs) ?: return SourceEqualityResidual.Declined
                for (index in 1 until equation.columns.size) {
                    val column = equation.columns[index]
                    val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO,
                        scale, equation.coefficients[index]) ?: return SourceEqualityResidual.Declined
                    if (value.isZero) coefficients.remove(column) else coefficients[column] = value
                }
            }
            coefficients.entries.removeAll { it.value.isZero }
            if (coefficients.isEmpty()) {
                if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                    onStage?.invoke("RECOVERY_EQUALITY_RESIDUAL_FALSE")
                    return if (stop()) SourceEqualityResidual.Declined else SourceEqualityResidual.Refuted
                }
                continue
            }
            entries += coefficients.size + 1L
            if (entries > MAX_REPAIR_ENTRIES) {
                onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
                return SourceEqualityResidual.Declined
            }
            stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
            if (stop()) return SourceEqualityResidual.Declined
            val ordered = coefficients.keys.sorted()
            residual += ExactRationalInequality(ordered.toIntArray(), ordered.map(coefficients::getValue), bound,
                row.strict)
        }
        return SourceEqualityResidual.Rows(residual)
    }

    fun completeResult(
        rows: List<ExactRationalInequality>,
        realCompletion: ((Int, List<ExactRationalInequality>, Cancellation) -> MixedPointCompletionResult)?,
    ): MixedPointCompletionResult {
        val reduced = reduceRows(rows)
        if (stop()) return MixedPointCompletionResult.Declined
        val residual = when (reduced) {
            is SourceEqualityResidual.Rows -> reduced.rows
            SourceEqualityResidual.Refuted -> return MixedPointCompletionResult.Refuted
            SourceEqualityResidual.Declined -> return MixedPointCompletionResult.Declined
        }
        val graph = RealDifferenceSystem.prepareRows(pivots.size, residual, stop)
        if (stop()) return MixedPointCompletionResult.Declined
        val free = if (graph == null) {
            onStage?.invoke("RECOVERY_EQUALITY_GRAPH_UNAVAILABLE")
            val completed = realCompletion?.invoke(pivots.size, residual, stop)
            if (stop()) return MixedPointCompletionResult.Declined
            when (completed) {
                is MixedPointCompletionResult.Point -> completed.values
                MixedPointCompletionResult.Refuted -> return MixedPointCompletionResult.Refuted
                else -> return MixedPointCompletionResult.Declined
            }
        } else {
            onStage?.invoke("RECOVERY_EQUALITY_GRAPH_ATTEMPT")
            val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
            if (stop()) return MixedPointCompletionResult.Declined
            if (result is RealDifferenceSystem.Result.Infeasible) return MixedPointCompletionResult.Refuted
            (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return MixedPointCompletionResult.Declined
        }
        onStage?.invoke("RECOVERY_EQUALITY_FREE_POINT")
        return point(free)?.let { MixedPointCompletionResult.Point(it) } ?: MixedPointCompletionResult.Declined
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)


private const val MAX_REPAIR_ENTRIES = 65536L
