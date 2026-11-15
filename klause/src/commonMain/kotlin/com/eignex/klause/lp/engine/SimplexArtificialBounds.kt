package com.eignex.klause.lp.engine

import kotlin.math.abs

// Artificial seats describe only the boxed LP; the coordinator withholds its bounds and rays.
internal class SimplexArtificialBounds(
    private val status: Array<VarStatus>,
    private val observations: SimplexObservations,
) {
    private var artificialLower: DoubleArray? = null
    private var artificialUpper: DoubleArray? = null
    var widenings = 0
        private set

    val active: Boolean get() = artificialLower != null

    fun finiteLowerAt(model: LpModel, column: Int): Boolean =
        model.hasFiniteLower(column) || artificialLower?.get(column)?.isNaN() == false

    fun finiteUpperAt(model: LpModel, column: Int): Boolean =
        model.hasFiniteUpper(column) || artificialUpper?.get(column)?.isNaN() == false

    fun lowerAt(numerical: LpScalingView, column: Int): Double {
        val artificial = artificialLower?.get(column)
        return if (artificial == null || artificial.isNaN()) numerical.lowerD(column) else artificial
    }

    fun upperAt(numerical: LpScalingView, column: Int): Double {
        val artificial = artificialUpper?.get(column)
        return if (artificial == null || artificial.isNaN()) numerical.upperD(column) else artificial
    }

    inline fun install(
        model: LpModel,
        numerical: LpScalingView,
        pricing: SimplexPhasePricing,
        tolerance: Double,
        dualFeasible: () -> Boolean,
    ): Boolean {
        val numVars = model.numVars
        val y = pricing.objectiveDuals()
        val width = artificialBoundWidth(model, numerical)
        val lower = DoubleArray(numVars) { Double.NaN }
        val upper = DoubleArray(numVars) { Double.NaN }
        var boxed = 0
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            val reduced = numerical.costD(j) - pricing.dotColumn(y, j)
            if (!reduced.isFinite()) return false
            val hasLower = model.hasFiniteLower(j)
            val hasUpper = model.hasFiniteUpper(j)
            when {
                reduced < -tolerance -> {
                    if (!hasUpper) {
                        upper[j] = if (hasLower) maxOf(width, numerical.lowerD(j) + width) else width
                        boxed++
                    }
                    status[j] = VarStatus.AT_UPPER
                }

                reduced > tolerance -> {
                    if (!hasLower) {
                        lower[j] = if (hasUpper) minOf(-width, numerical.upperD(j) - width) else -width
                        boxed++
                    }
                    status[j] = VarStatus.AT_LOWER
                }
            }
        }
        if (boxed > 0) {
            artificialLower = lower
            artificialUpper = upper
            widenings = 0
        }
        if (!dualFeasible()) return false
        if (boxed > 0) observations.numericalMetrics.artificialStarts++
        return true
    }

    private fun artificialBoundWidth(model: LpModel, numerical: LpScalingView): Double {
        val m = model.m
        val numVars = model.numVars
        var magnitude = 0.0
        for (i in 0 until m) {
            val value = abs(numerical.rhsD(i))
            if (value.isFinite()) magnitude = maxOf(magnitude, value)
        }
        for (j in 0 until numVars) {
            val lower = if (model.hasFiniteLower(j)) abs(numerical.lowerD(j)) else 0.0
            val upper = if (model.hasFiniteUpper(j)) abs(numerical.upperD(j)) else 0.0
            if (lower.isFinite()) magnitude = maxOf(magnitude, lower)
            if (upper.isFinite()) magnitude = maxOf(magnitude, upper)
        }
        return (ARTIFICIAL_BOUND_FACTOR * magnitude).coerceIn(ARTIFICIAL_BOUND_FLOOR, ARTIFICIAL_BOUND_CEILING)
    }

    fun seats(): Int {
        val numVars = status.size
        val lower = artificialLower ?: return 0
        val upper = artificialUpper ?: return 0
        var seated = 0
        for (j in 0 until numVars) {
            when (status[j]) {
                VarStatus.AT_LOWER -> if (!lower[j].isNaN()) seated++
                VarStatus.AT_UPPER -> if (!upper[j].isNaN()) seated++
                else -> Unit
            }
        }
        return seated
    }

    fun widenSeated() {
        val numVars = status.size
        val lower = artificialLower ?: return
        val upper = artificialUpper ?: return
        for (j in 0 until numVars) {
            when (status[j]) {
                VarStatus.AT_LOWER -> if (!lower[j].isNaN()) lower[j] *= ARTIFICIAL_BOUND_WIDENING
                VarStatus.AT_UPPER -> if (!upper[j].isNaN()) upper[j] *= ARTIFICIAL_BOUND_WIDENING
                else -> Unit
            }
        }
        widenings++
        observations.numericalMetrics.artificialWidenings++
    }

    fun drop(model: LpModel, finiteLower: BooleanArray?, finiteUpper: BooleanArray?) {
        val numVars = status.size
        clear()
        if (finiteLower != null) for (j in 0 until numVars) finiteLower[j] = model.hasFiniteLower(j)
        if (finiteUpper != null) for (j in 0 until numVars) finiteUpper[j] = model.hasFiniteUpper(j)
    }

    fun clear() {
        artificialLower = null
        artificialUpper = null
        widenings = 0
    }

    fun releaseDegenerate(
        model: LpModel,
        numerical: LpScalingView,
        pricing: SimplexPhasePricing,
        tolerance: Double,
    ): Int {
        val numVars = status.size
        val lower = artificialLower ?: return 0
        val upper = artificialUpper ?: return 0
        val y = pricing.objectiveDuals()
        var released = 0
        for (j in 0 until numVars) {
            val seated = when (status[j]) {
                VarStatus.AT_LOWER -> !lower[j].isNaN()
                VarStatus.AT_UPPER -> !upper[j].isNaN()
                else -> false
            }
            if (!seated || abs(numerical.costD(j) - pricing.dotColumn(y, j)) > tolerance) continue
            status[j] = when {
                model.hasFiniteLower(j) -> VarStatus.AT_LOWER
                model.hasFiniteUpper(j) -> VarStatus.AT_UPPER
                else -> VarStatus.FREE
            }
            released++
        }
        observations.numericalMetrics.artificialReleases += released
        return released
    }

    fun boundRange(model: LpModel, numerical: LpScalingView, column: Int): Double {
        val lower = artificialLower
        val upper = artificialUpper
        if (lower == null || upper == null || (lower[column].isNaN() && upper[column].isNaN())) {
            return numerical.boundRangeD(column)
        }
        if (!finiteLowerAt(model, column) || !finiteUpperAt(model, column)) return Double.MAX_VALUE
        return upperAt(numerical, column) - lowerAt(numerical, column)
    }

    private companion object {
        const val ARTIFICIAL_BOUND_FACTOR: Double = 1e3
        const val ARTIFICIAL_BOUND_FLOOR: Double = 1e6
        const val ARTIFICIAL_BOUND_CEILING: Double = 1e9
        const val ARTIFICIAL_BOUND_WIDENING: Double = 1e3
    }
}
