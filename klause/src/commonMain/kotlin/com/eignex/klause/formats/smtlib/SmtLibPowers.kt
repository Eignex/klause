package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.integerPowerOrNull
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.isConstant
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.toLongExact

private fun powerExponent(term: IntComb): Int {
    val value = when (term) {
        is IntComb.Narrow -> {
            if (!term.isConstant()) smtUnsupported("power requires a fixed nonnegative integer exponent")
            bigIntOf(term.lin.constant)
        }
        is IntComb.Wide -> {
            if (!term.isConstant()) smtUnsupported("power requires a fixed nonnegative integer exponent")
            term.lin.constant
        }
    }
    if (value < bigIntOf(0) || value > bigIntOf(Int.MAX_VALUE)) smtUnsupported("power exponent exceeds representation")
    return value.toLongExact().toInt()
}

internal fun Compiler.Builder.integerPower(base: IntComb, power: IntComb): IntComb {
    if (macros.isNotEmpty()) smtUnsupported("power requires lexical source lowering")
    val exponent = powerExponent(power)
    if (exponent == 0) return IntComb.Narrow(LinComb(emptyMap(), 1L))
    if (exponent == 1) return base
    val term = (base as? IntComb.Narrow)?.lin ?: smtUnsupported("wide power requires exact source lowering")
    if (term.coeffs.isEmpty()) {
        val value = integerPowerOrNull(term.constant, exponent) ?: smtUnsupported("constant power requires exact source lowering")
        return IntComb.Narrow(LinComb(emptyMap(), value))
    }
    if (inferringBounds) smtUnsupported("power requires source lowering")
    val (lo, hi) = finiteProductRange(term)
    val first = integerPowerOrNull(lo, exponent) ?: smtUnsupported("power exceeds signed word range")
    val last = integerPowerOrNull(hi, exponent) ?: smtUnsupported("power exceeds signed word range")
    val lower = if (exponent > 0 && exponent and 1 == 0 && lo <= 0 && hi >= 0) 0L else minOf(first, last)
    if (lower == Long.MIN_VALUE) smtUnsupported("power requires exact source lowering")
    val result = newInt(lower, maxOf(first, last))
    factors.add(Power(productIntColumn(term), exponent, result))
    return IntComb.Narrow(LinComb(mapOf(result to 1L), 0L))
}

internal fun Compiler.Builder.realPower(base: RealComb, power: IntComb): Res.R {
    if (macros.isNotEmpty()) smtUnsupported("power requires lexical source lowering")
    val exponent = powerExponent(power)
    if (exponent == 0) return Res.R(RealComb(emptyMap(), emptyMap(), BigFraction.ONE))
    if (exponent == 1) return Res.R(base)
    if (base.isConstant) {
        var remaining = exponent
        var value = BigFraction.ONE
        var factor = base.constant
        while (remaining > 0) {
            if (remaining and 1 != 0) value *= factor
            remaining = remaining ushr 1
            if (remaining > 0) factor *= factor
        }
        return Res.R(RealComb(emptyMap(), emptyMap(), value))
    }
    if (inferringBounds) smtUnsupported("power requires source lowering")
    val result = nextReal++
    factors.add(Power(Power.Coordinate.Real(SourceRealId(productRealColumn(base))), exponent,
        Power.Coordinate.Real(SourceRealId(result))))
    return Res.R(RealComb(emptyMap(), mapOf(result to BigFraction.ONE), BigFraction.ZERO))
}
