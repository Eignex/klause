package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Construction policy for the simplex's private numerical scaling view. */
internal data class LpScalingOptions(val enabled: Boolean = true, val equilibrationPasses: Int = 4) {
    init {
        require(equilibrationPasses in 1..8)
    }
}

internal enum class LpScalingDecline {
    DISABLED,
    ALREADY_CONDITIONED,
    NONFINITE_SOURCE,
    PROJECTION_LOSS,
    UNSAFE_TRANSFORM,
    UPDATE_UNSAFE,
}

/** Observable scaling decision and source-unit residuals for one numerical owner. */
internal data class LpScalingMetrics(
    val eligible: Boolean = false,
    val applied: Boolean = false,
    val fallbacks: Int = 0,
    val decline: LpScalingDecline? = null,
    val transformedValues: Int = 0,
    val beforeMatrixRatio: Double = 1.0,
    val beforeRowRatio: Double = 1.0,
    val beforeColumnRatio: Double = 1.0,
    val afterMatrixRatio: Double = 1.0,
    val afterRowRatio: Double = 1.0,
    val afterColumnRatio: Double = 1.0,
    val sourcePrimalResidual: Double = 0.0,
    val sourceBoundViolation: Double = 0.0,
    val sourceBasicDualResidual: Double = 0.0,
)

/**
 * A private numerical presentation of [source]. Exact authority never enters this object.
 *
 * With row exponents `p` and column exponents `q`, the simplex sees
 * `2^p M 2^q z = 2^p b`, where source shifted coordinates satisfy `x = 2^q z`.
 * Logical exponent `q[n+i] = -p[i]` keeps every logical column equal to `e_i`.
 */
internal class LpScalingView private constructor(
    val source: LpModel,
    val rowExponents: IntArray,
    val columnExponents: IntArray,
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: DoubleArray,
    private val rhs: DoubleArray,
    private val cost: DoubleArray,
    private val lower: DoubleArray,
    private val upper: DoubleArray,
    val metrics: LpScalingMetrics,
) {
    val applied: Boolean get() = metrics.applied
    val version: Long = scaleVersion(rowExponents, columnExponents)
    val n: Int get() = source.n
    val m: Int get() = source.m
    val numVars: Int get() = source.numVars

    fun rhsD(row: Int): Double = rhs[row]
    fun costD(column: Int): Double = cost[column]
    fun lowerD(column: Int): Double = lower[column]
    fun upperD(column: Int): Double = upper[column]

    fun boundRangeD(column: Int): Double {
        if (!source.hasFiniteLower(column) || !source.hasFiniteUpper(column)) return Double.MAX_VALUE
        val sourceRange = if (source.exactState == null) {
            source.upperD(column) - source.lowerD(column)
        } else {
            (source.exactUpper(column) - source.exactLower(column)).toDouble()
        }
        if (!sourceRange.isFinite()) return Double.MAX_VALUE
        return requireMapped(sourceRange, -columnExponents[column])
    }

    inline fun forEachInColumn(column: Int, action: (row: Int, value: Double) -> Unit) {
        for (k in colPtr[column] until colPtr[column + 1]) action(rowIdx[k], colVal[k])
    }

    fun sourceCoordinate(column: Int, scaled: Double): Double = requireMapped(scaled, columnExponents[column])
    fun sourceDual(row: Int, scaled: Double): Double = requireMapped(scaled, rowExponents[row])

    fun sourceDirection(scaled: DoubleArray): DoubleArray {
        require(scaled.size == numVars)
        return DoubleArray(numVars) { sourceCoordinate(it, scaled[it]) }
    }

    /** Refresh vectors under the immutable matrix scale. Null leaves this view untouched. */
    fun refresh(next: LpModel): LpScalingView? {
        if (next.n != n || next.m != m) return null
        var nextRhs = rhs
        var nextCost = cost
        var nextLower = lower
        var nextUpper = upper
        if (!applied) {
            for (i in rhs.indices) nextRhs = refreshedValue(rhs, nextRhs, i, next.rhsD(i))
            for (j in cost.indices) nextCost = refreshedValue(cost, nextCost, j, next.costD(j))
            for (j in lower.indices) nextLower = refreshedValue(lower, nextLower, j, next.lowerD(j))
            for (j in upper.indices) nextUpper = refreshedValue(upper, nextUpper, j, next.upperD(j))
        } else {
            if (projectionLostNonzero(next) || !next.objConstantD.isFinite()) return null
            for (i in rhs.indices) {
                val value = checkedScale(next.rhsD(i), rowExponents[i]) ?: return null
                nextRhs = refreshedValue(rhs, nextRhs, i, value)
            }
            for (j in cost.indices) {
                val costValue = checkedScale(next.costD(j), columnExponents[j]) ?: return null
                nextCost = refreshedValue(cost, nextCost, j, costValue)
                val lowerValue = checkedScale(next.lowerD(j), -columnExponents[j]) ?: return null
                nextLower = refreshedValue(lower, nextLower, j, lowerValue)
                val upperValue = if (next.hasFiniteUpper(j)) {
                    checkedScale(next.upperD(j), -columnExponents[j]) ?: return null
                } else {
                    0.0
                }
                nextUpper = refreshedValue(upper, nextUpper, j, upperValue)
            }
        }
        return LpScalingView(
            next,
            rowExponents,
            columnExponents,
            colPtr,
            rowIdx,
            colVal,
            nextRhs,
            nextCost,
            nextLower,
            nextUpper,
            metrics.copy(sourcePrimalResidual = 0.0, sourceBoundViolation = 0.0, sourceBasicDualResidual = 0.0),
        )
    }

    companion object {
        fun create(model: LpModel, options: LpScalingOptions = LpScalingOptions()): LpScalingView {
            val conditioning = lpConditioning(model)
            if (!options.enabled) {
                return identity(model, conditioning, LpScalingDecline.DISABLED, eligible = false)
            }
            if (conditioning.withinHighsNoScalingWindow) {
                return identity(model, conditioning, LpScalingDecline.ALREADY_CONDITIONED, eligible = false)
            }
            val sourceValues = sourceMatrix(model)
                ?: return identity(model, conditioning, LpScalingDecline.NONFINITE_SOURCE, eligible = true)
            if (projectionLostNonzero(model)) {
                return identity(model, conditioning, LpScalingDecline.PROJECTION_LOSS, eligible = true)
            }
            val rowExponents = IntArray(model.m)
            val columnExponents = IntArray(model.numVars)
            equilibrate(
                model.n,
                model.m,
                sourceValues.colPtr,
                sourceValues.rowIdx,
                sourceValues.values,
                rowExponents,
                columnExponents,
                options.equilibrationPasses,
            )
            for (i in 0 until model.m) columnExponents[model.n + i] = -rowExponents[i]

            val scaledValues = DoubleArray(sourceValues.values.size)
            for (j in 0 until model.n) {
                for (k in sourceValues.colPtr[j] until sourceValues.colPtr[j + 1]) {
                    val exponent = rowExponents[sourceValues.rowIdx[k]] + columnExponents[j]
                    scaledValues[k] = checkedScale(sourceValues.values[k], exponent)
                        ?: return identity(model, conditioning, LpScalingDecline.UNSAFE_TRANSFORM, eligible = true)
                }
            }
            val vectors = scaledVectors(model, rowExponents, columnExponents)
                ?: return identity(model, conditioning, LpScalingDecline.UNSAFE_TRANSFORM, eligible = true)
            val after = conditioning(
                model.n,
                model.m,
                sourceValues.colPtr,
                sourceValues.rowIdx,
                scaledValues,
            )
            return LpScalingView(
                model,
                rowExponents,
                columnExponents,
                sourceValues.colPtr,
                sourceValues.rowIdx,
                scaledValues,
                vectors.rhs,
                vectors.cost,
                vectors.lower,
                vectors.upper,
                LpScalingMetrics(
                    eligible = true,
                    applied = true,
                    transformedValues = scaledValues.size + model.m + 3 * model.numVars,
                    beforeMatrixRatio = conditioning.matrixRatio,
                    beforeRowRatio = conditioning.rowRatio,
                    beforeColumnRatio = conditioning.columnRatio,
                    afterMatrixRatio = after.matrixRatio,
                    afterRowRatio = after.rowRatio,
                    afterColumnRatio = after.columnRatio,
                ),
            )
        }

        fun identityAfterFallback(model: LpModel, previous: LpScalingMetrics): LpScalingView {
            val conditioning = lpConditioning(model)
            return identity(model, conditioning, LpScalingDecline.UPDATE_UNSAFE, eligible = previous.eligible).let {
                LpScalingView(
                    it.source,
                    it.rowExponents,
                    it.columnExponents,
                    it.colPtr,
                    it.rowIdx,
                    it.colVal,
                    it.rhs,
                    it.cost,
                    it.lower,
                    it.upper,
                    it.metrics.copy(fallbacks = previous.fallbacks + 1),
                )
            }
        }

        private fun identity(
            model: LpModel,
            conditioning: LpConditioning,
            decline: LpScalingDecline,
            eligible: Boolean,
        ): LpScalingView {
            val matrix = sourceMatrixUnchecked(model)
            val vectors = sourceVectors(model)
            return LpScalingView(
                model,
                IntArray(model.m),
                IntArray(model.numVars),
                matrix.colPtr,
                matrix.rowIdx,
                matrix.values,
                vectors.rhs,
                vectors.cost,
                vectors.lower,
                vectors.upper,
                LpScalingMetrics(
                    eligible = eligible,
                    applied = false,
                    decline = decline,
                    beforeMatrixRatio = conditioning.matrixRatio,
                    beforeRowRatio = conditioning.rowRatio,
                    beforeColumnRatio = conditioning.columnRatio,
                    afterMatrixRatio = conditioning.matrixRatio,
                    afterRowRatio = conditioning.rowRatio,
                    afterColumnRatio = conditioning.columnRatio,
                ),
            )
        }
    }
}

private class NumericalMatrix(val colPtr: IntArray, val rowIdx: IntArray, val values: DoubleArray)

private class NumericalVectors(
    val rhs: DoubleArray,
    val cost: DoubleArray,
    val lower: DoubleArray,
    val upper: DoubleArray,
)

private fun sourceMatrix(model: LpModel): NumericalMatrix? {
    val matrix = sourceMatrixUnchecked(model)
    return matrix.takeIf { it.values.all(Double::isFinite) }
}

private fun sourceMatrixUnchecked(model: LpModel): NumericalMatrix {
    val pointers = IntArray(model.n + 1)
    var entries = 0
    for (j in 0 until model.n) {
        model.forEachInColumnD(j) { _, _ -> entries++ }
        pointers[j + 1] = entries
    }
    val rows = IntArray(entries)
    val values = DoubleArray(entries)
    var k = 0
    for (j in 0 until model.n) {
        model.forEachInColumnD(j) { row, value ->
            rows[k] = row
            values[k] = value
            k++
        }
    }
    return NumericalMatrix(pointers, rows, values)
}

private fun refreshedValue(previous: DoubleArray, current: DoubleArray, index: Int, value: Double): DoubleArray {
    if (current[index].toRawBits() == value.toRawBits()) return current
    val next = if (current === previous) previous.copyOf() else current
    next[index] = value
    return next
}

private fun sourceVectors(model: LpModel): NumericalVectors = NumericalVectors(
    DoubleArray(model.m) { model.rhsD(it) },
    DoubleArray(model.numVars) { model.costD(it) },
    DoubleArray(model.numVars) { model.lowerD(it) },
    DoubleArray(model.numVars) { model.upperD(it) },
)

private fun scaledVectors(model: LpModel, rowExponents: IntArray, columnExponents: IntArray): NumericalVectors? {
    if (!model.objConstantD.isFinite()) return null
    val rhs = DoubleArray(model.m)
    val cost = DoubleArray(model.numVars)
    val lower = DoubleArray(model.numVars)
    val upper = DoubleArray(model.numVars)
    for (i in rhs.indices) rhs[i] = checkedScale(model.rhsD(i), rowExponents[i]) ?: return null
    for (j in cost.indices) {
        cost[j] = checkedScale(model.costD(j), columnExponents[j]) ?: return null
        lower[j] = checkedScale(model.lowerD(j), -columnExponents[j]) ?: return null
        upper[j] = if (model.hasFiniteUpper(j)) {
            checkedScale(model.upperD(j), -columnExponents[j]) ?: return null
        } else {
            0.0
        }
    }
    return NumericalVectors(rhs, cost, lower, upper)
}

private fun projectionLostNonzero(model: LpModel): Boolean {
    val exact = model.exactState?.model ?: return false
    for (i in 0 until model.m) if (!exact.rhs(i).value.isZero && model.rhsD(i) == 0.0) return true
    for (j in 0 until model.numVars) {
        if (!exact.objective.cost(j).value.isZero && model.costD(j) == 0.0) return true
        val bounds = exact.column(j).bounds
        if (bounds.lower?.number?.value?.isZero == false && model.lowerD(j) == 0.0) return true
        if (bounds.upper?.number?.value?.isZero == false && model.upperD(j) == 0.0) return true
        if (j < model.n && !exact.column(j).origin.value.isZero && model.loShiftD(j) == 0.0) return true
    }
    return false
}

private fun equilibrate(
    n: Int,
    m: Int,
    colPtr: IntArray,
    rowIdx: IntArray,
    values: DoubleArray,
    rowExponents: IntArray,
    columnExponents: IntArray,
    passes: Int,
) {
    val rowMaximum = DoubleArray(m)
    repeat(passes) {
        rowMaximum.fill(Double.NEGATIVE_INFINITY)
        for (j in 0 until n) {
            for (k in colPtr[j] until colPtr[j + 1]) {
                val row = rowIdx[k]
                rowMaximum[row] = maxOf(
                    rowMaximum[row],
                    binaryLog(abs(values[k])) + rowExponents[row] + columnExponents[j],
                )
            }
        }
        for (i in 0 until m) {
            if (rowMaximum[i].isFinite()) {
                rowExponents[i] = (rowExponents[i] - rowMaximum[i].roundToInt()).coerceIn(MIN_EXPONENT, MAX_EXPONENT)
            }
        }
        for (j in 0 until n) {
            var maximum = Double.NEGATIVE_INFINITY
            for (k in colPtr[j] until colPtr[j + 1]) {
                val row = rowIdx[k]
                maximum = maxOf(maximum, binaryLog(abs(values[k])) + rowExponents[row] + columnExponents[j])
            }
            if (maximum.isFinite()) {
                columnExponents[j] =
                    (columnExponents[j] - maximum.roundToInt()).coerceIn(MIN_EXPONENT, MAX_EXPONENT)
            }
        }
    }
}

private fun conditioning(n: Int, m: Int, colPtr: IntArray, rowIdx: IntArray, values: DoubleArray): LpConditioning {
    if (n == 0 || m == 0 || values.isEmpty()) return LpConditioning.EMPTY
    val rowMin = DoubleArray(m) { Double.MAX_VALUE }
    val rowMax = DoubleArray(m)
    var minimum = Double.MAX_VALUE
    var maximum = 0.0
    var columnRatio = 1.0
    for (j in 0 until n) {
        var columnMin = Double.MAX_VALUE
        var columnMax = 0.0
        for (k in colPtr[j] until colPtr[j + 1]) {
            val magnitude = abs(values[k])
            if (magnitude == 0.0) continue
            val row = rowIdx[k]
            minimum = minOf(minimum, magnitude)
            maximum = maxOf(maximum, magnitude)
            columnMin = minOf(columnMin, magnitude)
            columnMax = maxOf(columnMax, magnitude)
            rowMin[row] = minOf(rowMin[row], magnitude)
            rowMax[row] = maxOf(rowMax[row], magnitude)
        }
        if (columnMax > 0.0) columnRatio = maxOf(columnRatio, columnMax / columnMin)
    }
    var rowRatio = 1.0
    for (i in 0 until m) if (rowMax[i] > 0.0) rowRatio = maxOf(rowRatio, rowMax[i] / rowMin[i])
    return LpConditioning(minimum, maximum, rowRatio, columnRatio, values.size)
}

private fun binaryLog(value: Double): Double = ln(value) / LN_TWO

private fun requireMapped(value: Double, exponent: Int): Double =
    checkedScale(value, exponent) ?: throw LpScalingArithmeticException()

private fun checkedScale(value: Double, exponent: Int): Double? {
    if (!value.isFinite()) return null
    if (value == 0.0 || exponent == 0) return value
    var scaled = value
    var remaining = exponent
    while (remaining != 0) {
        val step = remaining.coerceIn(-EXPONENT_STEP, EXPONENT_STEP)
        scaled *= 2.0.pow(step)
        if (!scaled.isFinite() || scaled == 0.0) return null
        remaining -= step
    }
    if (scaled.isSubnormal()) return null
    var restored = scaled
    remaining = -exponent
    while (remaining != 0) {
        val step = remaining.coerceIn(-EXPONENT_STEP, EXPONENT_STEP)
        restored *= 2.0.pow(step)
        if (!restored.isFinite() || restored == 0.0) return null
        remaining -= step
    }
    return scaled.takeIf { restored.toRawBits() == value.toRawBits() }
}

private fun Double.isSubnormal(): Boolean = this != 0.0 && ((toRawBits() ushr 52) and 0x7ffL) == 0L

private fun scaleVersion(rows: IntArray, columns: IntArray): Long {
    var hash = -3750763034362895579L
    for (value in rows) hash = (hash xor value.toLong()) * 1099511628211L
    hash = (hash xor -1L) * 1099511628211L
    for (value in columns) hash = (hash xor value.toLong()) * 1099511628211L
    return hash
}

internal class LpScalingArithmeticException : ArithmeticException("unsafe scaled numerical mapping")

private const val MIN_EXPONENT = -1022
private const val MAX_EXPONENT = 1022
private const val EXPONENT_STEP = 512
private const val LN_TWO = 0.6931471805599453
