package com.eignex.klause.lp.engine

import kotlin.math.max
import kotlin.math.nextUp

/**
 * Displacement of a derived row: `sum(k, multiplier(k) * max(absolute, relative * magnitude(k)))`.
 * Each nonnegative multiplier prices one premise's feasibility band; magnitudes bound its relative scale.
 */
class LpRowSlack(multipliers: DoubleArray, magnitudes: DoubleArray) {
    private val weights = multipliers.copyOf()
    private val scales = magnitudes.copyOf()

    init {
        require(weights.size == scales.size)
        require(weights.all { it.isFinite() && it >= 0.0 })
        require(scales.all { it >= 0.0 })
    }

    /** Number of premise bands. */
    val size: Int get() = weights.size

    /** Nonnegative multiplier of premise [index]. */
    fun multiplier(index: Int): Double = weights[index]

    /** Upper bound on the relative scale of premise [index], possibly infinite. */
    fun magnitude(index: Int): Double = scales[index]

    internal fun at(slack: LpBoundSlack): Double {
        if (slack == LpBoundSlack.NONE) return 0.0
        var sum = 0.0
        for (index in weights.indices) {
            if (weights[index] == 0.0) continue
            val band = if (slack.relative == 0.0) slack.absolute
            else max(slack.absolute, (slack.relative * scales[index]).nextUp())
            sum = (sum + (weights[index] * band).nextUp()).nextUp()
        }
        return sum
    }

    override fun equals(other: Any?): Boolean = other is LpRowSlack &&
        weights.contentEquals(other.weights) && scales.contentEquals(other.scales)

    override fun hashCode(): Int = 31 * weights.contentHashCode() + scales.contentHashCode()
}
