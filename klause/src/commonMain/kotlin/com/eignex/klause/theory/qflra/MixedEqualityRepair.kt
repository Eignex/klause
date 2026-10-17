package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal sealed interface SourceEqualityPreparation {
    class Ready(val repair: MixedEqualityRepair) : SourceEqualityPreparation
    data object Refuted : SourceEqualityPreparation
    data object Declined : SourceEqualityPreparation
}

internal sealed interface SourceEqualityResidual {
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
): SourceEqualityPreparation = prepareEqualities(columns, rows, stop, onStage, null)

private fun prepareEqualities(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
    trace: SourceEqualityTrace?,
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
    for ((rowIndex, row) in rows.withIndex()) {
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
        val direction = if (reversed) 2 else 1
        if (trace == null) {
            val next = previous or direction
            directions[key] = next
            if (next == 3 && previous != 3) equalities += key
        } else {
            val flags = previous and 3
            if (flags == 0) {
                stop.charge(3L)
                if (stop()) return SourceEqualityPreparation.Declined
                directions[key] = ((rowIndex + 1) shl 2) or direction
            } else if (flags and direction == 0) {
                if (!trace.pair(equalities.size, (previous ushr 2) - 1, rowIndex)) {
                    return SourceEqualityPreparation.Declined
                }
                directions[key] = previous or direction
                equalities += key
            }
        }
    }
    if (equalities.isEmpty()) {
        onStage?.invoke("RECOVERY_EQUALITY_NO_PAIRS")
        return SourceEqualityPreparation.Declined
    }
    onStage?.invoke("RECOVERY_EQUALITY_PAIRED")
    stop.charge(3L * columns)
    if (stop()) return SourceEqualityPreparation.Declined
    val pivots = arrayOfNulls<ExactRationalInequality>(columns)
    for ((equalityIndex, equality) in equalities.withIndex()) {
        if (trace != null && !trace.beginEquality(equalityIndex)) return SourceEqualityPreparation.Declined
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
                if (trace != null && !trace.retainPivot(pivot)) return SourceEqualityPreparation.Declined
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
            if (trace != null && !scale.isZero && !trace.unionPivot(pivot)) {
                return SourceEqualityPreparation.Declined
            }
        }
        if (coefficients.isEmpty() && !bound.isZero) {
            if (trace != null && !trace.publish()) return SourceEqualityPreparation.Declined
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

    fun checkedResidual(
        rows: List<ExactRationalInequality>,
        trace: SourceEqualityTrace? = null,
    ): SourceEqualityResidual {
        if (trace != null && !trace.matches(rows)) return SourceEqualityResidual.Declined
        var entries = 0L
        for (row in rows) {
            stop.charge(row.columns.size.toLong() + 1L)
            entries += row.columns.size + 1L
            if (entries > MAX_REPAIR_ENTRIES || stop()) return SourceEqualityResidual.Declined
            if (row.columns.any { it !in pivots.indices } ||
                !row.coefficients.all(work::admitted) || !work.admitted(row.rhs)
            ) return SourceEqualityResidual.Declined
        }
        return reduceRows(rows, trace)
    }

    private fun reduceRows(
        rows: List<ExactRationalInequality>,
        trace: SourceEqualityTrace? = null,
    ): SourceEqualityResidual {
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for ((rowIndex, row) in rows.withIndex()) {
            if (trace != null && !trace.beginResidual(rowIndex)) return SourceEqualityResidual.Declined
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
                if (trace != null && !scale.isZero && !trace.unionPivot(pivot)) {
                    return SourceEqualityResidual.Declined
                }
            }
            coefficients.entries.removeAll { it.value.isZero }
            if (coefficients.isEmpty()) {
                if (if (row.strict) BigFraction.ZERO >= bound else BigFraction.ZERO > bound) {
                    onStage?.invoke("RECOVERY_EQUALITY_RESIDUAL_FALSE")
                    if (trace != null && !trace.publish()) return SourceEqualityResidual.Declined
                    return if (stop()) SourceEqualityResidual.Declined else SourceEqualityResidual.Refuted
                }
                continue
            }
            if (trace != null) continue
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

internal sealed interface SourceEqualityProof {
    class Refuted(val support: SourceEqualityRowSupport) : SourceEqualityProof
    data object Unavailable : SourceEqualityProof
    data object Declined : SourceEqualityProof
}

internal class SourceEqualityRowSupport private constructor(
    private val bits: LongArray,
    private val rows: Int,
) {
    fun visit(stop: Cancellation, visitor: (Int) -> Boolean): Boolean {
        var visited = false
        for (word in bits.indices) {
            stop.charge(1L)
            if (stop()) return false
            var remaining = bits[word]
            while (remaining != 0L) {
                stop.charge(1L)
                if (stop()) return false
                val row = word * 64 + remaining.countTrailingZeroBits()
                if (row >= rows || !visitor(row)) return false
                visited = true
                remaining = remaining and (remaining - 1L)
            }
        }
        return visited && !stop()
    }

    companion object {
        fun capture(
            bits: LongArray,
            offset: Int,
            words: Int,
            rows: Int,
            stop: Cancellation,
        ): SourceEqualityRowSupport? {
            stop.charge(2L * words + 8L)
            if (stop()) return null
            val copy = bits.copyOfRange(offset, offset + words)
            return if (stop()) null else SourceEqualityRowSupport(copy, rows)
        }
    }
}

internal fun refuteCapturedSourceEqualities(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    originCells: Long = 0L,
    captureEntries: Long = 0L,
    onStage: ((String) -> Unit)? = null,
): SourceEqualityProof = SourceEqualityTrace.refute(columns, rows, stop, originCells, captureEntries, onStage)

internal class SourceEqualityTrace private constructor(
    private val rows: List<ExactRationalInequality>,
    columns: Int,
    private val stop: Cancellation,
    private val onStage: ((String) -> Unit)?,
) {
    private val words = (rows.size + 63) / 64
    private val workOffset = columns * words
    private val bits = LongArray((columns + 1) * words)
    private val pairs = IntArray(rows.size)
    private var completed: SourceEqualityRowSupport? = null

    fun matches(input: List<ExactRationalInequality>): Boolean = input === rows

    fun pair(index: Int, first: Int, second: Int): Boolean {
        stop.charge(3L)
        if (stop() || first !in rows.indices || second !in rows.indices || 2L * index + 1L >= pairs.size) return false
        pairs[2 * index] = first
        pairs[2 * index + 1] = second
        return !stop()
    }

    fun beginEquality(index: Int): Boolean = begin(pairs[2 * index], pairs[2 * index + 1])

    fun beginResidual(row: Int): Boolean = begin(row)

    private fun begin(first: Int, second: Int? = null): Boolean {
        stop.charge(words.toLong() + 2L)
        if (stop()) return false
        bits.fill(0L, workOffset, workOffset + words)
        bits[workOffset + first / 64] = 1L shl (first % 64)
        if (second != null) {
            val word = workOffset + second / 64
            bits[word] = bits[word] or (1L shl (second % 64))
        }
        return !stop()
    }

    fun retainPivot(pivot: Int): Boolean {
        stop.charge(2L * words)
        if (stop()) return false
        bits.copyInto(bits, pivot * words, workOffset, workOffset + words)
        return !stop()
    }

    fun unionPivot(pivot: Int): Boolean {
        stop.charge(3L * words)
        if (stop()) return false
        for (word in 0 until words) {
            bits[workOffset + word] = bits[workOffset + word] or bits[pivot * words + word]
        }
        onStage?.invoke("RECOVERY_SUPPORT_UNION")
        return !stop()
    }

    fun publish(): Boolean {
        val support = SourceEqualityRowSupport.capture(bits, workOffset, words, rows.size, stop) ?: return false
        onStage?.invoke("RECOVERY_SUPPORT_OUTPUT")
        if (stop()) return false
        completed = support
        return true
    }

    private fun refute(columns: Int): SourceEqualityProof {
        val refuted = when (val prepared = prepareEqualities(columns, rows, stop, onStage, this)) {
            is SourceEqualityPreparation.Ready -> {
                onStage?.invoke("RECOVERY_SOURCE_EQUALITY_PREPARED")
                prepared.repair.checkedResidual(rows, this) === SourceEqualityResidual.Refuted
            }
            SourceEqualityPreparation.Refuted -> true
            SourceEqualityPreparation.Declined -> false
        }
        if (!refuted || stop()) return SourceEqualityProof.Declined
        return completed?.let { SourceEqualityProof.Refuted(it) } ?: SourceEqualityProof.Declined
    }

    companion object {
        fun refute(
            columns: Int,
            rows: List<ExactRationalInequality>,
            stop: Cancellation,
            originCells: Long,
            captureEntries: Long,
            onStage: ((String) -> Unit)?,
        ): SourceEqualityProof {
            if (stop()) return SourceEqualityProof.Declined
            if (!admitsSourceEqualityColumns(columns) || originCells !in 0L..MAX_REPAIR_ENTRIES ||
                captureEntries !in 0L..MAX_REPAIR_ENTRIES || rows.size > MAX_REPAIR_ENTRIES
            ) return SourceEqualityProof.Unavailable
            var terms = 0L
            for (row in rows) {
                stop.charge(1L)
                if (stop()) return SourceEqualityProof.Declined
                terms += row.columns.size
                if (terms + rows.size > MAX_REPAIR_ENTRIES) return SourceEqualityProof.Unavailable
            }
            val words = (rows.size.toLong() + 63L) / 64L
            val snapshotCells = 33L * rows.size + 2L * terms + 16L
            val added = originCells + snapshotCells + (columns + 2L) * words + 5L * rows.size + 128L
            if (columns.toLong() * columns + added > MAX_REPAIR_ENTRIES ||
                captureEntries + added > MAX_REPAIR_ENTRIES
            ) return SourceEqualityProof.Unavailable
            val initialized = snapshotCells + (columns + 1L) * words + rows.size + 128L
            stop.charge(3L * initialized)
            if (stop()) return SourceEqualityProof.Declined
            val copied = ArrayList<ExactRationalInequality>(rows.size)
            for (row in rows) {
                if (stop()) return SourceEqualityProof.Declined
                val coefficients = ArrayList<BigFraction>(row.coefficients.size)
                for (index in row.coefficients.indices) coefficients.add(row.coefficients[index])
                copied += ExactRationalInequality(row.columns.copyOf(), coefficients, row.rhs, row.strict)
            }
            if (stop()) return SourceEqualityProof.Declined
            val trace = SourceEqualityTrace(copied, columns, stop, onStage)
            if (stop()) return SourceEqualityProof.Declined
            onStage?.invoke("RECOVERY_SUPPORT_CAPTURED")
            return trace.refute(columns)
        }
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)


private const val MAX_REPAIR_ENTRIES = 65536L
