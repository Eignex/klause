package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal class BoundedWitnessRationals(
    private val arithmetic: PolynomialArithmetic,
    private val maximumBits: Int,
) {
    fun admitted(value: BigFraction): Boolean {
        arithmetic.step()
        return value.num.magnitudeBitLength() <= maximumBits && value.den.magnitudeBitLength() <= maximumBits
    }

    fun power(value: BigFraction, exponent: Int): BigFraction? {
        var result = BigFraction.ONE
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            arithmetic.step()
            if (remaining and 1 != 0) {
                result *= base
                if (!admitted(result)) return null
            }
            remaining = remaining ushr 1
            if (remaining != 0) {
                arithmetic.step()
                base *= base
                if (!admitted(base)) return null
            }
        }
        return result
    }
}
