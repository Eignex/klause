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
    refutesGeneral: ((Int, List<ExactRationalInequality>, Cancellation) -> Boolean)? = null,
): Boolean {
    val support = if (onSupport != null) IntArrayList() else null
    val proofs = if (onSupport != null) EqualityRowSupport(rows.size, stop) else null
    val residualOrigins = if (onSupport != null) IntArrayList() else null
    val residualProofs = if (onSupport != null) IntArrayList() else null
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
    var residualRefuted = false
    var proof = -1
    val refuted = when (val prepared = prepareEqualityRows(columns, rows, stop, onStage, paired,
        proofs, onRefutationProof = { proof = it })) {
        is EqualityRows.Prepared -> when (val residual = prepared.repair.reduceRows(rows,
            onReducedRow = if (support == null) null else { original, origin ->
                residualOrigins?.add(original)
                residualProofs?.add(origin)
            }, onFalseProof = if (proofs == null) null else { value -> proof = value },
            onFalseRow = { falseRow = it })) {
            EqualityResidual.Refuted -> true
            EqualityResidual.Declined -> false
            is EqualityResidual.Reduced -> refutesDifferenceResidual(columns, residual.rows, stop, onStage,
                refutesGeneral).also { residualRefuted = it }
        }
        EqualityRows.Refuted -> true
        EqualityRows.Declined -> false
    }
    if (refuted && support != null && !stop()) {
        if (residualRefuted) proof = proofs?.combineAll(checkNotNull(residualProofs)) ?: -1
        val tracked = proofs?.expand(proof)
        if (stop()) return false
        val selected = tracked ?: run {
            stop.charge(support.size.toLong() + (residualOrigins?.size ?: 0) + 2L)
            if (residualRefuted) support.addAll(checkNotNull(residualOrigins).toIntArray())
            else if (falseRow >= 0) support.add(falseRow)
            support.toIntArray()
        }
        onStage?.invoke(if (tracked == null) "PURE_REAL_EQUALITY_SUPPORT_CONSERVATIVE"
            else "PURE_REAL_EQUALITY_SUPPORT_TRACKED")
        onStage?.invoke("PURE_REAL_EQUALITY_SUPPORT_R_${selected.size}_FROM_${rows.size}")
        if (!stop()) onSupport?.invoke(selected)
    }
    return refuted && !stop()
}

private fun refutesDifferenceResidual(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)?,
    refutesGeneral: ((Int, List<ExactRationalInequality>, Cancellation) -> Boolean)?,
): Boolean {
    val graph = RealDifferenceSystem.prepareRows(columns, rows, stop)
    if (stop()) return false
    if (graph == null) {
        onStage?.invoke("PURE_REAL_EQUALITY_GRAPH_UNAVAILABLE")
        return refutesGeneral?.invoke(columns, rows, stop) == true && !stop()
    }
    onStage?.invoke("PURE_REAL_EQUALITY_GRAPH_ATTEMPT")
    val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
    if (stop() || result !is RealDifferenceSystem.Result.Infeasible) return false
    onStage?.invoke("PURE_REAL_EQUALITY_GRAPH_REFUTED")
    return !stop()
}

internal fun prepareEqualityRows(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
    onPairedRows: ((Int, Int) -> Unit)? = null,
    proofs: EqualityRowSupport? = null,
    onRefutationProof: ((Int) -> Unit)? = null,
): EqualityRows {
    if (!admitsEqualityRepairColumns(columns)) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return EqualityRows.Declined
    }
    if (stop()) return EqualityRows.Declined
    val work = MixedPointArithmetic(stop)
    val directions = HashMap<MixedEqualityKey, Int>()
    val firstRows = if (onPairedRows != null || proofs != null) HashMap<MixedEqualityKey, Int>() else null
    val equalities = ArrayList<MixedEqualityKey>()
    val equalityProofs = if (proofs != null) IntArrayList() else null
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
            if (proofs != null) equalityProofs?.add(
                proofs.combine(proofs.row(checkNotNull(firstRows).getValue(key)), proofs.row(rowIndex)),
            )
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
    val pivotProofs = if (proofs != null) IntArray(columns) { -1 } else null
    for ((equalityIndex, equality) in equalities.withIndex()) {
        stop.charge(3L * equality.terms.size + 1L)
        if (stop()) return EqualityRows.Declined
        val coefficients = equality.terms.toMap().toMutableMap()
        var bound = equality.bound
        var proof = equalityProofs?.get(equalityIndex) ?: -1
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
                pivotProofs?.set(pivot, proof)
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.first())
                ?: return EqualityRows.Declined
            if (proofs != null) proof = proofs.combine(proof, checkNotNull(pivotProofs)[pivot])
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
            if (!stop() && proof >= 0) onRefutationProof?.invoke(proof)
            return if (stop()) EqualityRows.Declined else EqualityRows.Refuted
        }
    }
    return if (stop()) EqualityRows.Declined else
        EqualityRows.Prepared(MixedEqualityRepair(pivots, stop, onStage, proofs, pivotProofs))
}

internal class MixedEqualityRepair(
    private val pivots: Array<ExactRationalInequality?>,
    private val stop: Cancellation,
    private val onStage: ((String) -> Unit)?,
    private val proofs: EqualityRowSupport? = null,
    private val pivotProofs: IntArray? = null,
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
        onReducedRow: ((Int, Int) -> Unit)? = null,
        onFalseProof: ((Int) -> Unit)? = null,
        onFalseRow: ((Int) -> Unit)? = null,
    ): EqualityResidual {
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for ((rowIndex, row) in rows.withIndex()) {
            stop.charge(3L * row.columns.size + pivots.size + 1L)
            if (stop() || !admittedEqualityRow(row, pivots.size, work)) return EqualityResidual.Declined
            val coefficients = row.columns.indices.associate { row.columns[it] to row.coefficients[it] }.toMutableMap()
            var bound = row.rhs
            var proof = if (onReducedRow != null || onFalseProof != null) proofs?.row(rowIndex) ?: -1 else -1
            for (pivot in pivots.indices) {
                val coefficient = coefficients[pivot] ?: continue
                val equation = pivots[pivot] ?: continue
                val scale = work.quotient(coefficient, equation.coefficients.first())
                    ?: return EqualityResidual.Declined
                if (proofs != null && !scale.isZero) {
                    proof = proofs.combine(proof, checkNotNull(pivotProofs)[pivot])
                }
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
                    if (proof >= 0 && !stop()) onFalseProof?.invoke(proof)
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
            if (onReducedRow != null) {
                stop.charge(2L)
                if (stop()) return EqualityResidual.Declined
                onReducedRow(rowIndex, proof)
            }
        }
        return if (stop()) EqualityResidual.Declined else EqualityResidual.Reduced(residual)
    }
}

internal class EqualityRowSupport(private val rows: Int, private val stop: Cancellation) {
    private val first = IntArrayList()
    private val second = IntArrayList()

    fun row(index: Int): Int = if (index !in 0 until rows) -1 else append(-1, index)

    fun combine(left: Int, right: Int): Int = when {
        left < 0 || right < 0 -> -1
        left == right -> left
        else -> append(left, right)
    }

    fun combineAll(roots: IntArrayList): Int {
        if (roots.isEmpty()) return -1
        var result = roots[0]
        for (index in 1 until roots.size) {
            stop.charge(1L)
            if (stop()) return -1
            result = combine(result, roots[index])
        }
        return result
    }

    fun expand(root: Int): IntArray? {
        if (root !in 0 until first.size || stop()) return null
        stop.charge(first.size.toLong() + 9L)
        if (stop()) return null
        val seen = BooleanArray(first.size)
        val pending = IntArrayList().also { it.add(root) }
        val selected = HashSet<Int>()
        while (!pending.isEmpty()) {
            stop.charge(4L)
            if (stop()) return null
            val node = pending.last()
            pending.truncateTo(pending.size - 1)
            if (node !in seen.indices) return null
            if (seen[node]) continue
            seen[node] = true
            val left = first[node]
            val right = second[node]
            if (left < 0) {
                if (right !in 0 until rows) return null
                selected += right
                if (selected.size > 4096) return null
            } else {
                if (left >= node || right !in 0 until node) return null
                pending.add(left)
                pending.add(right)
            }
        }
        stop.charge(selected.size.toLong() * (Int.SIZE_BITS - selected.size.countLeadingZeroBits() + 2L))
        return if (stop()) null else selected.sorted().toIntArray()
    }

    private fun append(left: Int, right: Int): Int {
        stop.charge(4L)
        if (first.size >= 8192 || stop()) return -1
        first.add(left)
        second.add(right)
        return first.size - 1
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)

private fun admittedEqualityRow(row: ExactRationalInequality, columns: Int, work: MixedPointArithmetic): Boolean =
    row.columns.size == row.coefficients.size && row.columns.all { it in 0 until columns } &&
        row.columns.indices.all { it == 0 || row.columns[it - 1] < row.columns[it] } &&
        row.coefficients.all(work::admitted) && work.admitted(row.rhs)


private const val MAX_REPAIR_ENTRIES = 65536L
