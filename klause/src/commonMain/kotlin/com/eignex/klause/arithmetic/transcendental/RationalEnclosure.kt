package com.eignex.klause.arithmetic.transcendental

import com.eignex.klause.simplex.exact.BigFraction

internal data class RationalEnclosure(val lower: BigFraction, val upper: BigFraction) {
    init { require(lower <= upper) }

    val isPoint: Boolean get() = lower == upper

    fun contains(value: BigFraction): Boolean = lower <= value && value <= upper

    companion object {
        fun point(value: BigFraction): RationalEnclosure = RationalEnclosure(value, value)
        val UNIT = RationalEnclosure(BigFraction.MINUS_ONE, BigFraction.ONE)
    }
}
