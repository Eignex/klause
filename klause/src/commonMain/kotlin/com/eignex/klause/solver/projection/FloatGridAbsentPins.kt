package com.eignex.klause.solver.projection

import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.lowering.approximateGridValue
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal class FloatGridAbsentPin(
    val present: SourceBoolId,
    val default: BigFraction,
    factors: List<Int>,
) {
    private val capturedFactors = factors.toList()
    val factors: List<Int> get() = capturedFactors.toList()
}

internal class FloatGridAbsentPins(
    val columns: List<FloatGridColumn>,
    val booleans: Int,
    val factors: List<Factor>,
    val origins: List<Int>,
    val pinRows: Map<Int, Pair<FloatGridColumn, BigFraction>>,
)

internal fun prepareFloatGridAbsentPins(
    source: Problem,
    variables: List<FloatGridVariable>,
    primary: List<FloatGridColumn>,
    cancellation: Cancellation,
): FloatGridAbsentPins? {
    val columns = ArrayList<FloatGridColumn>(primary.size)
    val factors = ArrayList<Factor>()
    val origins = ArrayList<Int>()
    val rows = HashMap<Int, Pair<FloatGridColumn, BigFraction>>()
    var booleans = source.numBoolVars
    var integers = source.numIntVars.toLong() + primary.size
    for ((index, variable) in variables.withIndex()) {
        cancellation.charge(1)
        if (cancellation()) return null
        val selector = primary[index]
        val pin = variable.absentPin
        if (pin == null) {
            columns += selector
            continue
        }
        if (pin.present.index !in 0 until source.numBoolVars || pin.factors.isEmpty()) return null
        val id = variable.source.index
        val lower = BigFraction.ofDouble(source.realLower[id])
        val upper = BigFraction.ofDouble(source.realUpper[id])
        if ((lower != null && pin.default < lower) || (upper != null && pin.default > upper)) return null
        val column = absentValueColumn(selector, pin.default, integers) ?: return null
        if (column !== selector) {
            integers++
            if (booleans > Int.MAX_VALUE - 2) return null
            val relation = presentChannel(selector, column) ?: return null
            factors += ReifiedLinear(booleans, relation.first, relation.second, LinearOp.EQ, relation.third)
            factors += Clause(intArrayOf(Lit.make(pin.present.index, false), Lit.make(booleans, true)))
            val nearest = if (selector.step.isZero) 0L else {
                val position = (pin.default - selector.origin) * selector.step.reciprocal()
                val rounded = -(position + BigFraction.ofLong(2).reciprocal()).negated().ceilInteger()
                when {
                    rounded < BIG_ZERO -> 0L
                    rounded >= bigIntOf(selector.coordinate.buckets) -> selector.coordinate.buckets - 1L
                    else -> rounded.toLongExact()
                }
            }
            factors += ReifiedLinear(booleans + 1, longArrayOf(1),
                intArrayOf(selector.coordinate.variable), LinearOp.EQ, nearest)
            factors += Clause(intArrayOf(Lit.make(pin.present.index, true), Lit.make(booleans + 1, true)))
            repeat(4) { origins += pin.factors.first() }
            booleans += 2
        }
        for (factor in pin.factors) {
            cancellation.charge(1)
            if (cancellation() || factor !in source.factors.indices || factor in rows ||
                lowerGridPin(source.factors[factor], column, pin.default) == null
            ) return null
            rows[factor] = column to pin.default
        }
        columns += column
    }
    return if (cancellation()) null else FloatGridAbsentPins(columns, booleans, factors, origins, rows)
}

private fun absentValueColumn(primary: FloatGridColumn, default: BigFraction, variable: Long): FloatGridColumn? {
    val origin: BigFraction
    val step: BigFraction
    val buckets: Int
    if (primary.step.isZero) {
        if (default == primary.origin) return primary
        origin = if (primary.origin < default) primary.origin else default
        step = (if (primary.origin > default) primary.origin else default) - origin
        buckets = 2
    } else {
        val position = (default - primary.origin) * primary.step.reciprocal()
        if (position.den == BIG_ONE && position.num >= BIG_ZERO &&
            position.num < bigIntOf(primary.coordinate.buckets)
        ) return primary
        val lower = if (position.num < BIG_ZERO) position.num else BIG_ZERO
        val last = position.den * bigIntOf(primary.coordinate.buckets - 1)
        val upper = if (last > position.num) last else position.num
        val count = upper - lower + BIG_ONE
        if (count > bigIntOf(Int.MAX_VALUE) || !count.fitsLong()) return null
        buckets = count.toLongExact().toInt()
        step = primary.step * BigFraction.of(BIG_ONE, position.den)
        origin = primary.origin + step * BigFraction.of(lower, BIG_ONE)
    }
    if (variable >= Int.MAX_VALUE) return null
    val end = origin + step * BigFraction.ofLong(buckets - 1L)
    val lo = approximateGridValue(origin)
    val hi = approximateGridValue(end)
    val increment = approximateGridValue(step)
    if (!lo.isFinite() || !hi.isFinite() || !(hi - lo).isFinite() ||
        !increment.isFinite() || increment <= 0.0 ||
        (!origin.isZero && lo == 0.0) || (!end.isZero && hi == 0.0)
    ) return null
    return FloatGridColumn(primary.source, GridCoordinate(variable.toInt(), lo, hi, buckets), origin, step, primary)
}

private fun presentChannel(
    primary: FloatGridColumn,
    value: FloatGridColumn,
): Triple<LongArray, IntArray, Long>? {
    val ratio = primary.step * value.step.reciprocal()
    val offset = (primary.origin - value.origin) * value.step.reciprocal()
    if (ratio.den != BIG_ONE || offset.den != BIG_ONE || !ratio.num.fitsLong() || !offset.num.fitsLong()) {
        return null
    }
    return Triple(longArrayOf(1, -ratio.num.toLongExact()),
        intArrayOf(value.coordinate.variable, primary.coordinate.variable), offset.num.toLongExact())
}

internal fun lowerGridPin(factor: Factor, column: FloatGridColumn, default: BigFraction): List<Factor>? {
    val operation: LinearOp
    val reifier: Int?
    when (factor) {
        is ReifiedRealLinear -> {
            if (factor.strict || factor.vars.isNotEmpty() ||
                !factor.realVars.contentEquals(intArrayOf(column.source.index)) ||
                !factor.realCoeffs.contentEquals(doubleArrayOf(1.0)) ||
                BigFraction.ofDouble(factor.bound) != default
            ) return null
            operation = factor.op
            reifier = factor.aux
        }
        is Linear -> {
            val row = factor.realConstants ?: return null
            if (row.strict || factor.vars.isNotEmpty() ||
                !factor.realVars.contentEquals(intArrayOf(column.source.index)) ||
                row.realCoefficients.at(0) != 1.0 || BigFraction.ofDouble(row.bound) != default
            ) return null
            operation = factor.op
            reifier = null
        }
        else -> return null
    }
    val coefficients: LongArray
    val bound: Long
    if (column.step.isZero) {
        if (column.origin != default) return null
        coefficients = longArrayOf(0)
        bound = 0
    } else {
        val position = (default - column.origin) * column.step.reciprocal()
        if (position.den != BIG_ONE || !position.num.fitsLong()) return null
        bound = position.num.toLongExact()
        if (!column.coordinate.contains(bound)) return null
        coefficients = longArrayOf(1)
    }
    val variables = intArrayOf(column.coordinate.variable)
    return listOf(if (reifier == null) Linear(coefficients, variables, operation, bound)
        else ReifiedLinear(reifier, coefficients, variables, operation, bound))
}
