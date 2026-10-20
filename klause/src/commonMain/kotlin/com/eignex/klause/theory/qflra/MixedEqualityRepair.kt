package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal fun prepareMixedEqualityRepair(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): MixedEqualityRepair? = (prepareEqualityRows(columns, rows, stop, onStage) as? EqualityRows.Prepared)?.repair

internal fun admitsEqualityRepairColumns(columns: Int): Boolean =
    columns > 0 && columns.toLong() * columns <= MAX_REPAIR_ENTRIES

internal sealed interface EqualityRows {
    class Prepared(val repair: MixedEqualityRepair) : EqualityRows
    data object Refuted : EqualityRows
    data object Declined : EqualityRows
}

internal sealed interface EqualityResidual {
    class Reduced(val rows: List<ExactRationalInequality>) : EqualityResidual
    data object Refuted : EqualityResidual
    data object Declined : EqualityResidual
}

internal fun equalityRowsRefute(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    onSupport: ((IntArray) -> Unit)? = null,
): Boolean {
    val support = if (onSupport != null) IntArrayList() else null
    val paired: ((Int, Int) -> Unit)? = support?.let { indices ->
        { first, second ->
            stop.charge(4L)
            if (!stop()) {
                indices.add(first)
                indices.add(second)
            }
        }
    }
    var falseRow = -1
    val refuted = when (val prepared = prepareEqualityRows(columns, rows, stop, onStage, paired)) {
        is EqualityRows.Prepared -> prepared.repair.reduceRows(rows) { falseRow = it } == EqualityResidual.Refuted
        EqualityRows.Refuted -> true
        EqualityRows.Declined -> false
    }
    if (refuted && support != null && !stop()) {
        stop.charge(support.size.toLong() + 2L)
        if (falseRow >= 0) support.add(falseRow)
        if (!stop()) onSupport?.invoke(support.toIntArray())
    }
    return refuted && !stop()
}

internal fun prepareEqualityRows(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    onPairedRows: ((Int, Int) -> Unit)? = null,
): EqualityRows {
    if (!admitsEqualityRepairColumns(columns)) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return EqualityRows.Declined
    }
    if (stop()) return EqualityRows.Declined
    val work = MixedPointArithmetic(stop)
    val directions = HashMap<MixedEqualityKey, Int>()
    val firstRows = if (onPairedRows != null) HashMap<MixedEqualityKey, Int>() else null
    val equalities = ArrayList<MixedEqualityKey>()
    var entries = 0L
    for ((rowIndex, row) in rows.withIndex()) {
        stop.charge(4L * row.columns.size + 3L)
        entries += row.columns.size + 1L
        if (entries > MAX_REPAIR_ENTRIES) {
            onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
            return EqualityRows.Declined
        }
        if (stop() || !admittedEqualityRow(row, columns, work)) return EqualityRows.Declined
        if (row.strict) continue
        val first = row.coefficients.firstOrNull { !it.isZero } ?: continue
        val reversed = first < BigFraction.ZERO
        val terms = row.columns.indices.filter { !row.coefficients[it].isZero }.map {
            row.columns[it] to if (reversed) row.coefficients[it].negated() else row.coefficients[it]
        }
        val key = MixedEqualityKey(terms, if (reversed) row.rhs.negated() else row.rhs)
        val previous = directions[key] ?: 0
        if (firstRows != null) {
            stop.charge(2L)
            if (stop()) return EqualityRows.Declined
            if (previous == 0) firstRows[key] = rowIndex
        }
        val next = previous or if (reversed) 2 else 1
        directions[key] = next
        if (next == 3 && previous != 3) {
            equalities += key
            if (firstRows != null) onPairedRows?.invoke(firstRows.getValue(key), rowIndex)
        }
    }
    if (equalities.isEmpty()) {
        onStage?.invoke("RECOVERY_EQUALITY_NO_PAIRS")
        return EqualityRows.Declined
    }
    onStage?.invoke("RECOVERY_EQUALITY_PAIRED")
    stop.charge(3L * columns)
    if (stop()) return EqualityRows.Declined
    val pivots = arrayOfNulls<ExactRationalInequality>(columns)
    for (equality in equalities) {
        stop.charge(3L * equality.terms.size + 1L)
        if (stop()) return EqualityRows.Declined
        val coefficients = equality.terms.toMap().toMutableMap()
        var bound = equality.bound
        while (coefficients.isNotEmpty()) {
            stop.charge(coefficients.size.toLong() + 1L)
            if (stop()) return EqualityRows.Declined
            val pivot = coefficients.keys.minOrNull() ?: return EqualityRows.Declined
            if (pivot !in pivots.indices) return EqualityRows.Declined
            val previous = pivots[pivot]
            if (previous == null) {
                stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
                if (stop()) return EqualityRows.Declined
                val ordered = coefficients.keys.sorted()
                pivots[pivot] = ExactRationalInequality(ordered.toIntArray(),
                    ordered.map(coefficients::getValue), bound)
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.first())
                ?: return EqualityRows.Declined
            coefficients.remove(pivot)
            bound = work.subtractProduct(bound, scale, previous.rhs) ?: return EqualityRows.Declined
            for (index in 1 until previous.columns.size) {
                val column = previous.columns[index]
                val coefficient = previous.coefficients[index]
                val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO, scale, coefficient)
                    ?: return EqualityRows.Declined
                if (value.isZero) coefficients.remove(column) else coefficients[column] = value
            }
        }
        if (coefficients.isEmpty() && !bound.isZero) {
            return if (stop()) EqualityRows.Declined else EqualityRows.Refuted
        }
    }
    return if (stop()) EqualityRows.Declined else EqualityRows.Prepared(MixedEqualityRepair(pivots, stop, onStage))
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

    fun completePoint(
        rows: List<ExactRationalInequality>,
        realCompletion: ((Int, List<ExactRationalInequality>, Cancellation) -> List<BigFraction>?)?,
    ): List<BigFraction>? {
        val residual = (reduceRows(rows) as? EqualityResidual.Reduced)?.rows ?: return null
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

    fun reduceRows(
        rows: List<ExactRationalInequality>,
        onFalseRow: ((Int) -> Unit)? = null,
    ): EqualityResidual {
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for ((rowIndex, row) in rows.withIndex()) {
            stop.charge(3L * row.columns.size + pivots.size + 1L)
            if (stop() || !admittedEqualityRow(row, pivots.size, work)) return EqualityResidual.Declined
            val coefficients = row.columns.indices.associate { row.columns[it] to row.coefficients[it] }.toMutableMap()
            var bound = row.rhs
            for (pivot in pivots.indices) {
                val coefficient = coefficients[pivot] ?: continue
                val equation = pivots[pivot] ?: continue
                val scale = work.quotient(coefficient, equation.coefficients.first())
                    ?: return EqualityResidual.Declined
                coefficients.remove(pivot)
                bound = work.subtractProduct(bound, scale, equation.rhs) ?: return EqualityResidual.Declined
                for (index in 1 until equation.columns.size) {
                    val column = equation.columns[index]
                    val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO,
                        scale, equation.coefficients[index]) ?: return EqualityResidual.Declined
                    if (value.isZero) coefficients.remove(column) else coefficients[column] = value
                }
            }
            coefficients.entries.removeAll { it.value.isZero }
            if (coefficients.isEmpty()) {
                if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                    onStage?.invoke("RECOVERY_EQUALITY_RESIDUAL_FALSE")
                    if (stop()) return EqualityResidual.Declined
                    onFalseRow?.invoke(rowIndex)
                    return if (stop()) EqualityResidual.Declined else EqualityResidual.Refuted
                }
                continue
            }
            entries += coefficients.size + 1L
            if (entries > MAX_REPAIR_ENTRIES) {
                onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
                return EqualityResidual.Declined
            }
            stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
            if (stop()) return EqualityResidual.Declined
            val ordered = coefficients.keys.sorted()
            residual += ExactRationalInequality(ordered.toIntArray(), ordered.map(coefficients::getValue), bound,
                row.strict)
        }
        return if (stop()) EqualityResidual.Declined else EqualityResidual.Reduced(residual)
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)

private fun admittedEqualityRow(row: ExactRationalInequality, columns: Int, work: MixedPointArithmetic): Boolean =
    row.columns.size == row.coefficients.size && row.columns.all { it in 0 until columns } &&
        row.columns.indices.all { it == 0 || row.columns[it - 1] < row.columns[it] } &&
        row.coefficients.all(work::admitted) && work.admitted(row.rhs)


private const val MAX_REPAIR_ENTRIES = 65536L
