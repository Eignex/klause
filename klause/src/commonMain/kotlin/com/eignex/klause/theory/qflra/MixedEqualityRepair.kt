package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal fun prepareMixedEqualityRepair(
    columns: Int,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): MixedEqualityRepair? {
    if (columns <= 0 || columns.toLong() * columns > MAX_REPAIR_ENTRIES) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return null
    }
    if (stop()) return null
    val work = MixedRepairWork(stop)
    val directions = HashMap<MixedEqualityKey, Int>()
    val equalities = ArrayList<MixedEqualityKey>()
    var entries = 0L
    for (row in rows) {
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
        val previous = directions[key] ?: 0
        val next = previous or if (reversed) 2 else 1
        directions[key] = next
        if (next == 3 && previous != 3) equalities += key
    }
    if (equalities.isEmpty()) {
        onStage?.invoke("RECOVERY_EQUALITY_NO_PAIRS")
        return null
    }
    onStage?.invoke("RECOVERY_EQUALITY_PAIRED")
    stop.charge(3L * columns)
    if (stop()) return null
    val pivots = arrayOfNulls<ExactRationalInequality>(columns)
    for (equality in equalities) {
        stop.charge(3L * equality.terms.size + 1L)
        if (stop()) return null
        val coefficients = equality.terms.toMap().toMutableMap()
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
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.first())
                ?: return null
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
    return MixedEqualityRepair(pivots, stop, onStage)
}

internal class MixedEqualityRepair(
    private val pivots: Array<ExactRationalInequality?>,
    private val stop: Cancellation,
    private val onStage: ((String) -> Unit)?,
) {
    private val work = MixedRepairWork(stop)

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

    fun differencePoint(rows: List<ExactRationalInequality>): List<BigFraction>? {
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for (row in rows) {
            stop.charge(3L * row.columns.size + pivots.size + 1L)
            if (stop()) return null
            val coefficients = row.columns.indices.associate { row.columns[it] to row.coefficients[it] }.toMutableMap()
            var bound = row.rhs
            for (pivot in pivots.indices) {
                val coefficient = coefficients[pivot] ?: continue
                val equation = pivots[pivot] ?: continue
                val scale = work.quotient(coefficient, equation.coefficients.first()) ?: return null
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
                    return null
                }
                continue
            }
            if (coefficients.size > 2) {
                onStage?.invoke("RECOVERY_EQUALITY_RESIDUAL_UNSUPPORTED")
                return null
            }
            entries += coefficients.size + 1L
            if (entries > MAX_REPAIR_ENTRIES) {
                onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
                return null
            }
            stop.charge(coefficients.size.toLong() * coefficients.size + 1L)
            if (stop()) return null
            val ordered = coefficients.keys.sorted()
            residual += ExactRationalInequality(ordered.toIntArray(), ordered.map(coefficients::getValue), bound,
                row.strict)
        }
        val graph = RealDifferenceSystem.prepareRows(pivots.size, residual, stop) ?: run {
            onStage?.invoke("RECOVERY_EQUALITY_GRAPH_UNAVAILABLE")
            return null
        }
        onStage?.invoke("RECOVERY_EQUALITY_GRAPH_ATTEMPT")
        val result = graph.check(intArrayOf(), stop) { stop.charge(it) }
        if (stop()) return null
        val free = (result as? RealDifferenceSystem.Result.Feasible)?.point ?: return null
        onStage?.invoke("RECOVERY_EQUALITY_GRAPH_FEASIBLE")
        return point(free)
    }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)

private class MixedRepairWork(private val stop: Cancellation) {
    fun admitted(value: BigFraction): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        stop.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_REPAIR_BITS && !stop()
    }

    private fun preflight(left: BigFraction, right: BigFraction): Boolean {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        if (bits > MAX_REPAIR_BITS * 2L + 2L) return false
        val words = bits / Long.SIZE_BITS + 1L
        stop.charge(bits + words * words)
        return !stop()
    }

    fun quotient(value: BigFraction, divisor: BigFraction): BigFraction? {
        if (divisor.isZero) return null
        val inverse = divisor.reciprocal()
        if (!preflight(value, inverse)) return null
        return (value * inverse).takeIf(::admitted)
    }

    fun subtractProduct(value: BigFraction, left: BigFraction, right: BigFraction): BigFraction? {
        if (!preflight(left, right)) return null
        val product = left * right
        if (!admitted(product) || !preflight(value, product)) return null
        return (value - product).takeIf(::admitted)
    }
}

private const val MAX_REPAIR_ENTRIES = 65536L
private const val MAX_REPAIR_BITS = 4096
