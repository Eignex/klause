package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal fun repairMixedEqualities(
    guess: List<BigFraction>,
    rows: List<ExactRationalInequality>,
    stop: Cancellation,
    onStage: ((String) -> Unit)? = null,
): List<BigFraction>? {
    val columns = guess.size
    if (columns.toLong() * columns > MAX_REPAIR_ENTRIES) {
        onStage?.invoke("RECOVERY_EQUALITY_LIMIT")
        return null
    }
    if (stop()) return null
    val work = MixedRepairWork(stop)
    if (!guess.all(work::admitted)) return null
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
    val pivots = arrayOfNulls<MixedRepairEquation>(columns)
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
                pivots[pivot] = MixedRepairEquation(coefficients, bound)
                onStage?.invoke("RECOVERY_EQUALITY_PIVOT")
                break
            }
            val scale = work.quotient(coefficients.getValue(pivot), previous.coefficients.getValue(pivot))
                ?: return null
            coefficients.remove(pivot)
            bound = work.subtractProduct(bound, scale, previous.bound) ?: return null
            for ((column, coefficient) in previous.coefficients) {
                if (column == pivot) continue
                val value = work.subtractProduct(coefficients[column] ?: BigFraction.ZERO, scale, coefficient)
                    ?: return null
                if (value.isZero) coefficients.remove(column) else coefficients[column] = value
            }
        }
        if (coefficients.isEmpty() && !bound.isZero) return null
    }
    val point = guess.toMutableList()
    for (pivot in pivots.indices.reversed()) {
        stop.charge(1L)
        if (stop()) return null
        val equation = pivots[pivot] ?: continue
        var value = equation.bound
        for ((column, coefficient) in equation.coefficients) {
            if (column != pivot) value = work.subtractProduct(value, coefficient, point[column]) ?: return null
        }
        point[pivot] = work.quotient(value, equation.coefficients.getValue(pivot)) ?: return null
    }
    return point.takeUnless { stop() }?.also { onStage?.invoke("RECOVERY_EQUALITY_RECONSTRUCTED") }
}

private data class MixedEqualityKey(val terms: List<Pair<Int, BigFraction>>, val bound: BigFraction)
private class MixedRepairEquation(val coefficients: Map<Int, BigFraction>, val bound: BigFraction)

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
