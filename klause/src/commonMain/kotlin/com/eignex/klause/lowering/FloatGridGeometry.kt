package com.eignex.klause.lowering

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.shl
import com.eignex.klause.util.toDouble

internal fun approximateGridValue(value: BigFraction): Double {
    val exponent = value.den.magnitudeBitLength() - 1
    if (exponent !in 0..1074 || value.den != BIG_ONE shl exponent) return value.toDouble()
    val scale = if (exponent <= 1022) Double.fromBits((1023L - exponent) shl 52)
        else Double.fromBits(1L shl (1074 - exponent))
    return value.num.toDouble() * scale
}
