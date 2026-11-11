package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.nextDown
import kotlin.math.nextUp

internal enum class LpFloatingBoundStop { WORK, CANCELLED, INPUT, NON_FINITE, UNBOUNDED }

internal data class LpFloatingBound(val bound: Double?, val work: Long, val stop: LpFloatingBoundStop?)

internal data class LpFloatingCost(
    val lower: Double?,
    val upper: Double?,
    val work: Long,
    val stop: LpFloatingBoundStop?,
)

internal data class LpFloatingConflict(
    val infeasible: Boolean,
    val margin: Double?,
    val work: Long,
    val stop: LpFloatingBoundStop?,
)

// This bound concerns the numerical projection. It is not an exact source certificate.
internal fun floatingObjectiveLowerBound(
    model: LpModel,
    duals: DoubleArray,
    workLimit: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
): LpFloatingBound = floatingLagrangianLowerBound(model, duals, workLimit, cancellation, objective = true)

@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun floatingReducedCost(
    model: LpModel,
    duals: DoubleArray,
    column: Int,
    workLimit: Long,
    cancellation: Cancellation = Cancellation.Never,
): LpFloatingCost {
    require(workLimit >= 0L)
    val meter = FloatingBoundMeter(workLimit, cancellation)
    val arithmetic = FloatingBoundArithmetic(meter)
    if (!meter.visit()) return meter.cost(null, null, LpFloatingBoundStop.WORK)
    if (column !in 0 until model.n || duals.size != model.m) {
        return meter.cost(null, null, LpFloatingBoundStop.INPUT)
    }
    var lower = model.costD(column)
    var upper = lower
    model.forEachInColumnD(column) { row, coefficient ->
        if (!meter.visit()) return meter.cost(null, null, LpFloatingBoundStop.WORK)
        val dual = duals[row]
        if (!coefficient.isFinite() || !dual.isFinite()) {
            return meter.cost(null, null, LpFloatingBoundStop.NON_FINITE)
        }
        lower = arithmetic.sumLower(lower, -arithmetic.productUpper(coefficient, dual))
        upper = arithmetic.sumUpper(upper, -arithmetic.productLower(coefficient, dual))
    }
    val scale = model.exactState?.model?.objective?.scale?.approximation ?: 1.0
    if (!scale.isFinite() || scale <= 0.0) return meter.cost(null, null, LpFloatingBoundStop.NON_FINITE)
    if (scale != 1.0) {
        if (!meter.visit()) return meter.cost(null, null, LpFloatingBoundStop.WORK)
        lower = (lower / scale).nextDown()
        upper = (upper / scale).nextUp()
    }
    if (!lower.isFinite() || !upper.isFinite()) return meter.cost(null, null, LpFloatingBoundStop.NON_FINITE)
    return meter.cost(lower, upper)
}

internal fun floatingInfeasibilityMargin(
    model: LpModel,
    ray: DoubleArray,
    workLimit: Long = Long.MAX_VALUE,
    cancellation: Cancellation = Cancellation.Never,
    tolerance: Double = FLOAT_EVIDENCE_TOLERANCE,
): LpFloatingConflict {
    require(tolerance.isFinite() && tolerance >= 0.0)
    val forward = floatingLagrangianLowerBound(model, ray, workLimit, cancellation, objective = false)
    if (forward.bound?.let { it > tolerance } == true) {
        return LpFloatingConflict(true, forward.bound, forward.work, null)
    }
    if (forward.stop != null && forward.stop != LpFloatingBoundStop.UNBOUNDED) {
        return LpFloatingConflict(false, null, forward.work, forward.stop)
    }
    val reverse = floatingLagrangianLowerBound(model, ray, workLimit - forward.work, cancellation,
        objective = false, dualSign = -1.0)
    val margin = when {
        forward.bound == null -> reverse.bound
        reverse.bound == null -> forward.bound
        else -> maxOf(forward.bound, reverse.bound)
    }
    return LpFloatingConflict(reverse.bound?.let { it > tolerance } == true, margin,
        forward.work + reverse.work, reverse.stop)
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private fun floatingLagrangianLowerBound(
    model: LpModel,
    duals: DoubleArray,
    workLimit: Long,
    cancellation: Cancellation,
    objective: Boolean,
    dualSign: Double = 1.0,
): LpFloatingBound {
    require(workLimit >= 0L)
    val meter = FloatingBoundMeter(workLimit, cancellation)
    val arithmetic = FloatingBoundArithmetic(meter)
    if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
    if (duals.size != model.m) return meter.refuse(LpFloatingBoundStop.INPUT)
    var bound = if (objective) {
        model.exactConstant().lowerBoundDouble() ?: return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    } else 0.0
    if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    for (row in 0 until model.m) {
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        val dual = duals[row] * dualSign
        val rhs = model.rhsD(row)
        if (!dual.isFinite() || !rhs.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        bound = arithmetic.sumLower(bound, arithmetic.productLower(dual, rhs))
        if (!bound.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
    }
    for (column in 0 until model.numVars) {
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        var reducedLower = if (objective) model.costD(column) else 0.0
        var reducedUpper = reducedLower
        if (!reducedLower.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        if (column < model.n) {
            model.forEachInColumnD(column) { row, coefficient ->
                if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
                if (!coefficient.isFinite()) return meter.refuse(LpFloatingBoundStop.NON_FINITE)
                val dual = duals[row] * dualSign
                reducedLower = arithmetic.sumLower(reducedLower, -arithmetic.productUpper(coefficient, dual))
                reducedUpper = arithmetic.sumUpper(reducedUpper, -arithmetic.productLower(coefficient, dual))
            }
        } else {
            val dual = duals[column - model.n] * dualSign
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
    model.exactState?.model?.objective?.takeIf { objective }?.let { source ->
        val scale = source.scale.approximation
        val constant = source.externalConstant.value.lowerBoundDouble()
            ?: return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        if (!scale.isFinite() || scale <= 0.0 || !constant.isFinite()) {
            return meter.refuse(LpFloatingBoundStop.NON_FINITE)
        }
        if (!meter.visit()) return meter.refuse(LpFloatingBoundStop.WORK)
        bound = when {
            bound == 0.0 -> 0.0
            scale == 1.0 -> bound
            else -> (bound / scale).nextDown()
        }
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

    fun cost(lower: Double?, upper: Double?, reason: LpFloatingBoundStop? = null): LpFloatingCost {
        val stopped = stop ?: if (cancellation()) LpFloatingBoundStop.CANCELLED else reason
        return LpFloatingCost(lower.takeIf { stopped == null }, upper.takeIf { stopped == null }, work, stopped)
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
