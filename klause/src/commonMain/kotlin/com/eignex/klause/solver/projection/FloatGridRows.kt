package com.eignex.klause.solver.projection

import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem as bigRemainder
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

private class FloatGridRow(
    val integers: IntArray,
    val integerCoefficients: DoubleArray,
    val reals: IntArray,
    val realCoefficients: DoubleArray,
    val operation: LinearOp,
    val bound: Double,
    val strict: Boolean,
    val reifier: Int?,
)

internal fun lowerGridRow(
    factor: Linear,
    columns: Map<Int, FloatGridColumn>,
    scale: Long,
    cancellation: Cancellation,
): List<Factor>? {
    val constants = factor.realConstants ?: return null
    return lowerGridRow(FloatGridRow(factor.vars, DoubleArray(factor.vars.size) { constants.intCoefficients.at(it) },
        factor.realVars, DoubleArray(factor.realVars.size) { constants.realCoefficients.at(it) },
        factor.op, constants.bound, constants.strict, null), columns, scale, cancellation)
}

internal fun lowerGridRow(
    factor: ReifiedRealLinear,
    columns: Map<Int, FloatGridColumn>,
    scale: Long,
    cancellation: Cancellation,
): List<Factor>? = lowerGridRow(FloatGridRow(factor.vars, factor.intCoeffs, factor.realVars, factor.realCoeffs,
    factor.op, factor.bound, factor.strict, factor.aux), columns, scale, cancellation)

private fun lowerGridRow(
    row: FloatGridRow,
    columns: Map<Int, FloatGridColumn>,
    scale: Long,
    cancellation: Cancellation,
): List<Factor>? = with(row) {
    val terms = LinkedHashMap<Int, BigFraction>()
    var right = BigFraction.ofDouble(bound) ?: return null
    val multiplier = BigFraction.ofLong(scale)
    for (index in integers.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coefficient = BigFraction.ofDouble(integerCoefficients[index]) ?: return null
        val variable = integers[index]
        terms[variable] = (terms[variable] ?: BigFraction.ZERO) + coefficient
    }
    for (index in reals.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val grid = columns[reals[index]] ?: return null
        val coefficient = BigFraction.ofDouble(realCoefficients[index]) ?: return null
        val variable = grid.coordinate.variable
        terms[variable] = (terms[variable] ?: BigFraction.ZERO) + coefficient * grid.step
        right -= coefficient * grid.origin
    }
    val variables = IntArray(terms.size)
    val coefficients = LongArray(terms.size)
    for ((index, entry) in terms.entries.withIndex()) {
        cancellation.charge(1)
        if (cancellation()) return null
        variables[index] = entry.key
        coefficients[index] = nearestInteger(entry.value * multiplier).word() ?: return null
    }
    val scaled = right * multiplier
    val integerBound = if (strict) {
        when (operation) {
            LinearOp.LE -> scaled.ceilInteger() - BIG_ONE
            LinearOp.GE -> -scaled.negated().ceilInteger() + BIG_ONE
            else -> return null
        }
    } else nearestInteger(scaled)
    val loweredBound = integerBound.word() ?: return null
    if (cancellation()) return null
    return listOf(if (reifier == null) Linear(coefficients, variables, operation, loweredBound)
        else ReifiedLinear(reifier, coefficients, variables, operation, loweredBound))
}

private fun nearestInteger(value: BigFraction): BigInt {
    val lower = -value.negated().ceilInteger()
    val remainder = (value - BigFraction.of(lower, BIG_ONE)) * BigFraction.ofLong(2)
    return when {
        remainder < BigFraction.ONE -> lower
        remainder > BigFraction.ONE -> lower + BIG_ONE
        lower.bigRemainder(BIG_TWO).isZero() -> lower
        else -> lower + BIG_ONE
    }
}

private fun BigInt.word(): Long? = if (fitsLong()) toLongExact() else null

internal fun prepareGridAbsImage(
    input: GridCoordinate,
    output: GridCoordinate,
    cancellation: Cancellation,
): IntArray? {
    cancellation.charge(input.buckets.toLong())
    if (cancellation()) return null
    val outputStep = if (output.buckets > 1) (output.upper - output.lower) / (output.buckets - 1) else 0.0
    val image = IntArray(input.buckets)
    for (index in image.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val magnitude = abs(input.value(index.toLong()))
        image[index] = if (magnitude < output.lower - outputStep * 0.5 ||
            magnitude > output.upper + outputStep * 0.5
        ) -1 else {
            val position = if (outputStep == 0.0) 0.0 else (magnitude - output.lower) / outputStep
            val rounded = round(position)
            if (!rounded.isFinite() || abs(position - rounded) > 0.5 || rounded < 0 || rounded >= output.buckets) -1
            else rounded.toInt()
        }
    }
    return if (cancellation()) null else image
}

internal fun lowerGridElement(
    factor: RealElement,
    columns: Map<Int, FloatGridColumn>,
    cancellation: Cancellation,
): List<Factor>? {
    if (factor.size == 0) return listOf(Clause(intArrayOf()))
    val output = columns.getValue(factor.result).coordinate
    val entries = LongArray(factor.size)
    for (index in entries.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        entries[index] = if (factor.arrIsVars) {
            val input = columns.getValue(factor.entries[index]).coordinate
            if (input.lower != output.lower || input.upper != output.upper || input.buckets != output.buckets) return null
            input.variable.toLong()
        } else output.index(factor.constants[index]) ?: return null
    }
    return if (cancellation()) null else listOf(Element(factor.idx, output.variable, entries,
        factor.arrIsVars, factor.indexOffset))
}

internal fun lowerGridMinMax(
    factor: RealArrayMinMax,
    columns: Map<Int, FloatGridColumn>,
    scale: Long,
    cancellation: Cancellation,
): GridArrayMinMax? {
    val roles = intArrayOf(factor.result) + factor.xs
    val coordinates = IntArray(roles.size)
    val steps = DoubleArray(roles.size)
    val origins = DoubleArray(roles.size)
    val constants = BooleanArray(roles.size)
    for (index in roles.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coordinate = columns.getValue(roles[index]).coordinate
        coordinates[index] = coordinate.variable
        steps[index] = if (coordinate.buckets > 1) (coordinate.upper - coordinate.lower) / (coordinate.buckets - 1)
            else 0.0
        origins[index] = coordinate.lower
        constants[index] = coordinate.buckets == 1
    }
    if (constants.any { !it }) {
        val scaledOrigins = LongArray(roles.size)
        for (index in roles.indices) {
            cancellation.charge(1)
            if (cancellation()) return null
            val origin = origins[index] * scale
            val step = steps[index] * scale
            if (!origin.isFinite() || !step.isFinite() ||
                origin <= Long.MIN_VALUE.toDouble() || origin >= Long.MAX_VALUE.toDouble() ||
                step >= Long.MAX_VALUE.toDouble()
            ) return null
            scaledOrigins[index] = origin.roundToLong()
        }
        for (origin in scaledOrigins) {
            cancellation.charge(1)
            val difference = bigIntOf(origin) - bigIntOf(scaledOrigins[0])
            if (cancellation() || !difference.fitsLong() || !(-difference).fitsLong()) return null
        }
    }
    return if (cancellation()) null else GridArrayMinMax(coordinates, steps, origins, constants, scale, factor.max)
}
