package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.nextDown
import kotlin.math.nextUp

internal enum class LpFloatingBoundStop { WORK, CANCELLED, INPUT, NON_FINITE, UNBOUNDED }

internal data class LpFloatingBound(val bound: Double?, val work: Long, val stop: LpFloatingBoundStop?)

// This bound concerns the numerical projection. It is not an exact source certificate.
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun floatingObjectiveLowerBound(
    model: LpModel,
    duals: DoubleArray,
    workLimit: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
): LpFloatingBound {
    require(workLimit >= 0L)
    val meter = FloatingBoundMeter(workLimit, cancellation)
    val arithmetic = FloatingBoundArithmetic(meter)
    if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
    if (duals.size != model.m) return meter.refuse(LpFloatingBoundStop.INPUT)
    var bound = model.objConstantD
    if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    for (row in 0 until model.m) {
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        val dual = duals[row]
        val rhs = model.rhsD(row)
        if (!dual.isFinite() || !rhs.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        bound = arithmetic.sumLower(bound, arithmetic.productLower(dual, rhs))
        if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    }
    for (column in 0 until model.numVars) {
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        var reducedLower = model.costD(column)
        var reducedUpper = reducedLower
        if (!reducedLower.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        if (column < model.n) {
            model.forEachInColumnD(column) { row, coefficient ->
                if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
                if (!coefficient.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
                reducedLower = arithmetic.sumLower(reducedLower, -arithmetic.productUpper(coefficient, duals[row]))
                reducedUpper = arithmetic.sumUpper(reducedUpper, -arithmetic.productLower(coefficient, duals[row]))
            }
        } else {
            val dual = duals[column - model.n]
            reducedLower = arithmetic.sumLower(reducedLower, -dual)
            reducedUpper = arithmetic.sumUpper(reducedUpper, -dual)
        }
        if (!reducedLower.isFinite() || !reducedUpper.isFinite()) {
            return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        }
        val lower = if (model.hasFiniteLower(column) &&
            !model.probeClampedLo.getOrElse(column) { false }
        ) model.lowerD(column) else Double.NEGATIVE_INFINITY
        val upper = if (model.hasFiniteUpper(column) &&
            !model.probeClampedHi.getOrElse(column) { false }
        ) model.upperD(column) else Double.POSITIVE_INFINITY
        if (lower.isNaN() || upper.isNaN() || lower > upper) return meter.refuse(LpFloatingBoundStop.INPUT)
        if (reducedLower == 0.0 && reducedUpper == 0.0) continue
        if ((!lower.isFinite() && reducedUpper > 0.0) || (!upper.isFinite() && reducedLower < 0.0)) {
            return meter.refuse(LpFloatingBoundStop.UNBOUNDED)
        }
        var contribution = Double.POSITIVE_INFINITY
        if (lower.isFinite()) {
            contribution = minOf(arithmetic.productLower(reducedLower, lower),
                arithmetic.productLower(reducedUpper, lower))
        }
        if (upper.isFinite()) {
            contribution = minOf(contribution, arithmetic.productLower(reducedLower, upper),
                arithmetic.productLower(reducedUpper, upper))
        }
        bound = arithmetic.sumLower(bound, contribution)
        if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    }
    model.exactState?.model?.objective?.let { objective ->
        val scale = objective.scale.approximation
        val constant = objective.externalConstant.approximation
        if (!scale.isFinite() || scale <= 0.0 || !constant.isFinite()) {
            return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        }
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        bound = if (bound == 0.0) 0.0 else (bound / scale).nextDown()
        bound = arithmetic.sumLower(bound, constant)
    }
    if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    return meter.finish(bound)
}

private class FloatingBoundMeter(private val limit: Long, private val cancellation: Cancellation) {
    var work = 0L
        private set
    private var stop: LpFloatingBoundStop? = null

    fun visit(): Boolean {
        if (stop != null) return false
        if (cancellation()) stop = LpFloatingBoundStop.CANCELLED
        else if (work == limit) stop = LpFloatingBoundStop.WORK
        else work++
        return stop == null
    }

    fun refuse(reason: LpFloatingBoundStop): LpFloatingBound = LpFloatingBound(null, work, stop ?: reason)

    fun finish(bound: Double): LpFloatingBound {
        if (cancellation()) return refuse(LpFloatingBoundStop.CANCELLED)
        return LpFloatingBound(bound, work, null)
    }
}

private class FloatingBoundArithmetic(private val meter: FloatingBoundMeter) {
    private val arithmetic = ExactDoubleArithmetic()

    fun sumLower(a: Double, b: Double): Double {
        if (!meter.visit()) return Double.NaN
        arithmetic.reset()
        val value = arithmetic.sum(a, b)
        return if (arithmetic.rounded) value.nextDown() else value
    }

    fun sumUpper(a: Double, b: Double): Double {
        if (!meter.visit()) return Double.NaN
        arithmetic.reset()
        val value = arithmetic.sum(a, b)
        return if (arithmetic.rounded) value.nextUp() else value
    }

    fun productLower(a: Double, b: Double): Double {
        if (!meter.visit()) return Double.NaN
        arithmetic.reset()
        val value = arithmetic.product(a, b)
        return if (arithmetic.rounded) value.nextDown() else value
    }

    fun productUpper(a: Double, b: Double): Double {
        if (!meter.visit()) return Double.NaN
        arithmetic.reset()
        val value = arithmetic.product(a, b)
        return if (arithmetic.rounded) value.nextUp() else value
    }
}
