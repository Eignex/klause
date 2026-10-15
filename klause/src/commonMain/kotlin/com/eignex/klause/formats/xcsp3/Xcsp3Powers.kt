package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.integerPowerOrNull
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.isConstant

internal fun Compiler.Builder.powerTerm(args: List<FExpr>): IntComb {
    if (args.size != 2) unsupportedPower("pow requires two arguments")
    val power = linear(args[1]) as? IntComb.Narrow
        ?: unsupportedPower("pow requires a fixed nonnegative Int exponent")
    if (!power.isConstant() || power.lin.constant !in 0L..Int.MAX_VALUE.toLong()) {
        unsupportedPower("pow requires a fixed nonnegative Int exponent")
    }
    val exponent = power.lin.constant.toInt()
    val base = materializeVar(linear(args[0]))
    val domain = domains[base]
    val first = integerPowerOrNull(domain.min, exponent)
        ?: unsupportedPower("pow exceeds signed word range")
    val last = integerPowerOrNull(domain.max, exponent)
        ?: unsupportedPower("pow exceeds signed word range")
    val even = exponent > 0 && exponent and 1 == 0
    val lower = if (even && domain.min <= 0 && domain.max >= 0) 0L else minOf(first, last)
    val result = newAuxVar(lower, maxOf(first, last))
    factors.add(Power(base, exponent, result))
    return IntComb.Narrow(LinComb(mapOf(result to 1L), 0L))
}

private fun unsupportedPower(message: String): Nothing = throw UnsupportedXcsp3Exception(message)
