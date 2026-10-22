package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class MixedPointArithmetic(private val stop: Cancellation) {
    fun admitted(value: BigFraction): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        stop.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_POINT_BITS && !stop()
    }

    fun satisfies(point: List<BigFraction>, rows: List<ExactRationalInequality>): Boolean {
        if (!point.all(::admitted)) return false
        for (row in rows) {
            var slack = row.rhs
            if (!admitted(slack)) return false
            for (index in row.columns.indices) {
                slack = subtractProduct(slack, row.coefficients[index], point[row.columns[index]]) ?: return false
            }
            if (if (row.strict) slack <= BigFraction.ZERO else slack < BigFraction.ZERO) return false
        }
        return !stop()
    }

    fun preflight(left: BigFraction, right: BigFraction): Boolean {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        if (bits > MAX_POINT_BITS * 2L + 2L) return false
        val words = bits / Long.SIZE_BITS + 1L
        stop.charge(bits + words * words)
        return !stop()
    }

    fun quotient(value: BigFraction, divisor: BigFraction): BigFraction? {
        if (divisor.isZero) return null
        val inverse = divisor.reciprocal()
        if (!preflight(value, inverse)) return null
        return (value * inverse).takeIf(::admitted)
    }

    fun subtractProduct(value: BigFraction, left: BigFraction, right: BigFraction): BigFraction? {
        if (!preflight(left, right)) return null
        val product = left * right
        if (!admitted(product) || !preflight(value, product)) return null
        return (value - product).takeIf(::admitted)
    }
}


private const val MAX_POINT_BITS = 4096
