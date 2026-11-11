package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.nextDown
import kotlin.math.nextUp

internal data class FloatingRowInterval(val lower: Double, val upper: Double) {
    val zero: Boolean get() = lower == 0.0 && upper == 0.0
    fun negated(): FloatingRowInterval = FloatingRowInterval(-upper, -lower)
}

internal class FloatingRowStop : RuntimeException()

internal class FloatingRowWork(
    private val limit: Long,
    private val allocationLimit: Long,
    private val cancellation: Cancellation,
) {
    var work = 0L
        private set
    private var allocation = 0L
    var stop: LpFloatingBoundStop? = null
        private set

    init {
        require(limit >= 0L && allocationLimit >= 0L)
    }

    fun visit(units: Long = 1L, bytes: Long = 0L) {
        if (cancellation()) refuse(LpFloatingBoundStop.CANCELLED)
        if (units > limit - work || bytes > allocationLimit - allocation) refuse(LpFloatingBoundStop.WORK)
        work += units
        allocation += bytes
    }

    fun refuse(reason: LpFloatingBoundStop): Nothing {
        stop = reason
        throw FloatingRowStop()
    }
}

internal class FloatingRowArithmetic(private val meter: FloatingRowWork) {
    private val arithmetic = ExactDoubleArithmetic()

    fun sumLower(a: Double, b: Double): Double {
        meter.visit()
        arithmetic.reset()
        val value = arithmetic.sum(a, b)
        return if (arithmetic.rounded) value.nextDown() else value
    }

    fun sumUpper(a: Double, b: Double): Double {
        meter.visit()
        arithmetic.reset()
        val value = arithmetic.sum(a, b)
        return if (arithmetic.rounded) value.nextUp() else value
    }

    fun productLower(a: Double, b: Double): Double {
        meter.visit(bytes = 96L)
        arithmetic.reset()
        val value = arithmetic.product(a, b)
        return if (arithmetic.rounded) value.nextDown() else value
    }

    private fun productUpper(a: Double, b: Double): Double {
        meter.visit(bytes = 96L)
        arithmetic.reset()
        val value = arithmetic.product(a, b)
        return if (arithmetic.rounded) value.nextUp() else value
    }

    fun add(a: FloatingRowInterval, b: FloatingRowInterval): FloatingRowInterval {
        meter.visit(bytes = 32L)
        return FloatingRowInterval(sumLower(a.lower, b.lower), sumUpper(a.upper, b.upper)).finite()
    }

    fun scale(value: FloatingRowInterval, multiplier: Double): FloatingRowInterval {
        meter.visit(bytes = 32L)
        val lower = if (multiplier >= 0.0) value.lower else value.upper
        val upper = if (multiplier >= 0.0) value.upper else value.lower
        return FloatingRowInterval(productLower(lower, multiplier), productUpper(upper, multiplier)).finite()
    }

    private fun FloatingRowInterval.finite(): FloatingRowInterval {
        if (!lower.isFinite() || !upper.isFinite()) meter.refuse(LpFloatingBoundStop.NON_FINITE)
        return this
    }
}
