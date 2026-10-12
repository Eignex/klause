package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus

internal fun AlgebraicArithmetic.ceilInteger(value: AlgebraicReal): BigInt {
    arithmetic.step()
    value.rational?.let { return it.ceilInteger() }
    var lower = value.lowerBound.ceilInteger()
    var upper = value.upperBound.ceilInteger()
    while (lower < upper) {
        arithmetic.step()
        val middle = -BigFraction.of(-(lower + upper), BIG_TWO).ceilInteger()
        if (compare(value, rational(BigFraction.of(middle, BIG_ONE))) <= 0) upper = middle
        else lower = middle + BIG_ONE
    }
    return lower
}

internal fun AlgebraicArithmetic.exactInteger(value: AlgebraicReal): BigInt? {
    val ceiling = ceilInteger(value)
    return if (compare(value, rational(BigFraction.of(ceiling, BIG_ONE))) == 0) ceiling else null
}
