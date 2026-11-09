package com.eignex.klause.solver.projection

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact

internal fun lowerGridFiniteDomain(
    factor: RealFiniteDomain,
    column: FloatGridColumn,
    cancellation: Cancellation,
): List<Factor>? = lowerGridFiniteValues(factor.size, factor::valueAt, column, cancellation)

internal fun lowerGridFiniteValues(
    size: Int,
    valueAt: (Int) -> Double,
    column: FloatGridColumn,
    cancellation: Cancellation,
): List<Factor>? {
    val admitted = LongArrayList()
    val seen = LongHashSet()
    for (index in 0 until size) {
        cancellation.charge(1)
        if (cancellation()) return null
        val value = requireNotNull(BigFraction.ofDouble(valueAt(index)))
        if (column.step.isZero) {
            if (value == column.origin) {
                return listOf(Linear(longArrayOf(1L), intArrayOf(column.coordinate.variable), LinearOp.GE, 0L))
            }
        } else {
            val position = (value - column.origin) * column.step.reciprocal()
            if (position.den == BIG_ONE && position.num.fitsLong()) {
                val bucket = position.num.toLongExact()
                if (column.coordinate.contains(bucket) && seen.add(bucket)) admitted.add(bucket)
            }
        }
    }
    if (cancellation()) return null
    return if (admitted.isEmpty()) {
        listOf(Linear(longArrayOf(1L), intArrayOf(column.coordinate.variable), LinearOp.LE, -1L))
    } else listOf(Table(intArrayOf(column.coordinate.variable), admitted.toLongArray()))
}
