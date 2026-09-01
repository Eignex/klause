package com.eignex.klause.lp

import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.UnimodularTransform
import com.eignex.klause.lp.lattice.hermiteNormalForm
import com.eignex.klause.lp.lattice.sparseIntRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/** One exact double-bounded row over real columns followed by integer columns. */
internal class ExactMixedBoundedRow(
    val coefficients: Map<Int, BigFraction>,
    val lower: BigFraction,
    val upper: BigFraction,
) {
    init {
        require(lower <= upper) { "exact mixed row has crossed bounds" }
    }
}

/**
 * Mixed-echelon/Hermite form of a double-bounded system.
 *
 * The first [realColumns] transformed variables are rational and the remaining columns are integral.
 * [recover] maps the transformed point back to the source coordinate order. Integer columns are changed
 * only by a unimodular transform, so the integer lattice is preserved exactly.
 */
internal class ExactMixedEchelonHermite(
    val rows: List<ExactMixedBoundedRow>,
    val realColumns: Int,
    val integerColumns: Int,
    private val transform: ExactMixedTransform,
) {
    fun recover(values: List<BigFraction>): List<BigFraction> = transform.recover(values)
}

/** Exact ranges implied by a mixed lower-triangular double-bounded system. */
internal class ExactMixedTriangularBounds(
    val realLower: Array<BigFraction?>,
    val realUpper: Array<BigFraction?>,
    val integerLower: Array<BigInteger?>,
    val integerUpper: Array<BigInteger?>,
) {
    val inconsistent: Boolean = integerLower.indices.any { index ->
        val lower = integerLower[index]
        val upper = integerUpper[index]
        lower != null && upper != null && lower > upper
    }
}

/**
 * Forward-substitute the transformed double-bounded rows into exact rational and integer ranges.
 *
 * Rational pivot columns retain rational bounds. Integer pivot columns use inward rounded division, so
 * an empty integer interval is an exact refutation rather than a relaxation artefact. A zero column is
 * deliberately left open: it belongs to the DBR extension lane and is not searched by the bounded phase.
 */
internal fun exactMixedTriangularBounds(system: ExactMixedEchelonHermite): ExactMixedTriangularBounds {
    val realLower = arrayOfNulls<BigFraction>(system.realColumns)
    val realUpper = arrayOfNulls<BigFraction>(system.realColumns)
    val integerLower = arrayOfNulls<BigInteger>(system.integerColumns)
    val integerUpper = arrayOfNulls<BigInteger>(system.integerColumns)
    for (row in system.rows) {
        val pivot = row.coefficients.keys.maxOrNull() ?: continue
        var restLower: BigFraction? = BigFraction.ZERO
        var restUpper: BigFraction? = BigFraction.ZERO
        for ((column, coefficient) in row.coefficients) {
            if (column >= pivot) continue
            val (lower, upper) = if (column < system.realColumns) {
                realLower[column] to realUpper[column]
            } else {
                val integer = column - system.realColumns
                integerLower[integer]?.let { BigFraction.of(it, BigInteger.ONE) } to
                    integerUpper[integer]?.let { BigFraction.of(it, BigInteger.ONE) }
            }
            val termLower = if (coefficient > BigFraction.ZERO) lower?.times(coefficient) else upper?.times(coefficient)
            val termUpper = if (coefficient > BigFraction.ZERO) upper?.times(coefficient) else lower?.times(coefficient)
            restLower = if (restLower == null || termLower == null) null else restLower + termLower
            restUpper = if (restUpper == null || termUpper == null) null else restUpper + termUpper
        }
        val productLower = restUpper?.let { row.lower - it }
        val productUpper = restLower?.let { row.upper - it }
        val coefficient = checkNotNull(row.coefficients[pivot])
        if (pivot < system.realColumns) {
            val (lower, upper) = divideRange(productLower, productUpper, coefficient)
            realLower[pivot] = lower
            realUpper[pivot] = upper
        } else {
            val (lower, upper) = divideRange(productLower, productUpper, coefficient)
            val integer = pivot - system.realColumns
            integerLower[integer] = lower?.ceil()
            integerUpper[integer] = upper?.floor()
        }
    }
    return ExactMixedTriangularBounds(realLower, realUpper, integerLower, integerUpper)
}

private fun divideRange(
    lower: BigFraction?,
    upper: BigFraction?,
    coefficient: BigFraction,
): Pair<BigFraction?, BigFraction?> = if (coefficient > BigFraction.ZERO) {
    lower?.times(coefficient.reciprocal()) to upper?.times(coefficient.reciprocal())
} else {
    upper?.times(coefficient.reciprocal()) to lower?.times(coefficient.reciprocal())
}

private fun BigFraction.floor(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private fun BigFraction.ceil(): BigInteger {
    val quotient = num / den
    return if (num > BigInteger.ZERO && num % den != BigInteger.ZERO) quotient + BigInteger.ONE else quotient
}

/**
 * Transform a double-bounded mixed system to an echelon rational block and a Hermite integer tail.
 *
 * Rational column operations isolate a real pivot without ever mixing an integer source column into a
 * real transformed column. They may add a rational source column to an integer transformed column,
 * which preserves integrality of the latter. The remaining integer matrix is row-scaled by positive
 * denominators and handed to the lattice layer for its unimodular Hermite reduction.
 */
internal fun exactMixedEchelonHermite(
    source: List<ExactMixedBoundedRow>,
    realColumns: Int,
    integerColumns: Int,
    cancellation: Cancellation = Cancellation.Never,
): ExactMixedEchelonHermite? {
    val columns = realColumns + integerColumns
    val rows = source.map { row -> MutableMixedRow(HashMap(row.coefficients), row.lower, row.upper) }.toMutableList()
    val transform = ExactMixedTransform(columns)
    var pivot = 0
    while (pivot < realColumns) {
        if (cancellation()) return null
        var pivotRow = -1
        var pivotColumn = -1
        for (row in pivot until rows.size) {
            val candidate = (pivot until realColumns).firstOrNull { column -> !rows[row][column].isZero }
            if (candidate != null) {
                pivotRow = row
                pivotColumn = candidate
                break
            }
        }
        if (pivotRow < 0) break
        rows.swap(pivot, pivotRow)
        swapColumns(rows, transform, pivot, pivotColumn)
        val coefficient = rows[pivot][pivot]
        scaleColumn(rows, transform, pivot, coefficient.reciprocal())
        for (column in 0 until columns) {
            if (column == pivot) continue
            val value = rows[pivot][column]
            if (!value.isZero) addColumnMultiple(rows, transform, column, pivot, value.negated())
        }
        pivot++
    }

    for (row in rows) row.integerize(realColumns, integerColumns)
    val integerRows = rows.map { row -> row.integerRow(realColumns, integerColumns) }
    val hermite = hermiteNormalForm(integerRows, integerColumns, cancellation) ?: return null
    composeIntegerTransform(transform, hermite.v, realColumns, integerColumns)
    for (index in rows.indices) rows[index].replaceIntegerTail(hermite.h[index], realColumns)
    return ExactMixedEchelonHermite(
        rows.map { row -> ExactMixedBoundedRow(row.coefficients, row.lower, row.upper) },
        realColumns,
        integerColumns,
        transform,
    )
}

private class MutableMixedRow(
    val coefficients: MutableMap<Int, BigFraction>,
    var lower: BigFraction,
    var upper: BigFraction,
) {
    operator fun get(column: Int): BigFraction = coefficients[column] ?: BigFraction.ZERO

    operator fun set(column: Int, value: BigFraction) {
        if (value.isZero) coefficients.remove(column) else coefficients[column] = value
    }

    fun integerize(realColumns: Int, integerColumns: Int) {
        var denominator = BigInteger.ONE
        for (column in realColumns until realColumns + integerColumns) {
            val value = this[column]
            denominator = (denominator * value.den) / denominator.gcd(value.den)
        }
        if (denominator == BigInteger.ONE) return
        val factor = BigFraction.of(denominator, BigInteger.ONE)
        for ((column, value) in coefficients.toMap()) this[column] = value * factor
        lower *= factor
        upper *= factor
    }

    fun integerRow(realColumns: Int, integerColumns: Int): SparseIntRow {
        val entries = HashMap<Int, BigInteger>()
        for (column in 0 until integerColumns) {
            val value = this[realColumns + column]
            check(value.den == BigInteger.ONE) { "integer Hermite tail still has a rational coefficient" }
            if (!value.isZero) entries[column] = value.num
        }
        return sparseIntRow(entries)
    }

    fun replaceIntegerTail(row: SparseIntRow, realColumns: Int) {
        coefficients.keys.filter { it >= realColumns }.forEach(coefficients::remove)
        for (index in row.index.indices) {
            coefficients[realColumns + row.index[index]] = BigFraction.of(row.value[index], BigInteger.ONE)
        }
    }
}

internal class ExactMixedTransform(columns: Int) {
    private var values = Array(columns) { column -> hashMapOf(column to BigFraction.ONE) }

    fun swap(x: Int, y: Int) {
        if (x == y) return
        val value = values[x]
        values[x] = values[y]
        values[y] = value
    }

    fun scale(column: Int, factor: BigFraction) {
        for ((row, value) in values[column].toMap()) values[column][row] = value * factor
    }

    fun addMultiple(target: Int, source: Int, factor: BigFraction) {
        if (target == source || factor.isZero) return
        for ((row, value) in values[source]) {
            val next = (values[target][row] ?: BigFraction.ZERO) + value * factor
            if (next.isZero) values[target].remove(row) else values[target][row] = next
        }
    }

    fun recover(point: List<BigFraction>): List<BigFraction> {
        require(point.size == values.size) { "mixed transform point has wrong width" }
        val result = MutableList(values.size) { BigFraction.ZERO }
        for (column in values.indices) for ((row, value) in values[column]) result[row] += value * point[column]
        return result
    }

    fun column(column: Int): Map<Int, BigFraction> = values[column]

    fun replaceColumn(column: Int, value: Map<Int, BigFraction>) {
        values[column] = HashMap(value)
    }
}

private fun swapColumns(rows: List<MutableMixedRow>, transform: ExactMixedTransform, x: Int, y: Int) {
    if (x == y) return
    for (row in rows) {
        val left = row[x]
        row[x] = row[y]
        row[y] = left
    }
    transform.swap(x, y)
}

private fun <T> MutableList<T>.swap(x: Int, y: Int) {
    if (x == y) return
    val value = this[x]
    this[x] = this[y]
    this[y] = value
}

private fun scaleColumn(rows: List<MutableMixedRow>, transform: ExactMixedTransform, column: Int, factor: BigFraction) {
    for (row in rows) row[column] *= factor
    transform.scale(column, factor)
}

private fun addColumnMultiple(
    rows: List<MutableMixedRow>,
    transform: ExactMixedTransform,
    target: Int,
    source: Int,
    factor: BigFraction,
) {
    for (row in rows) row[target] = row[target] + row[source] * factor
    transform.addMultiple(target, source, factor)
}

private fun composeIntegerTransform(
    transform: ExactMixedTransform,
    integer: UnimodularTransform,
    realColumns: Int,
    integerColumns: Int,
) {
    val old = List(integerColumns) { column -> transform.column(realColumns + column) }
    for (column in 0 until integerColumns) {
        val combined = HashMap<Int, BigFraction>()
        for (source in 0 until integerColumns) {
            val factor = integer[source, column]
            if (factor.isZero()) continue
            val rational = BigFraction.of(factor, BigInteger.ONE)
            for ((row, value) in old[source]) {
                val next = (combined[row] ?: BigFraction.ZERO) + value * rational
                if (next.isZero) combined.remove(row) else combined[row] = next
            }
        }
        transform.replaceColumn(realColumns + column, combined)
    }
}
