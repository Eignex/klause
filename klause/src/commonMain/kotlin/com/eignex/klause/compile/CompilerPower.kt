package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.integerPowerOrNull
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntRef

internal fun Lowering.liftPower(base: IntExpr, exponent: Int): IntExpr {
    val operand = intVarOf(materializeIntVar(lift(base)).name)
    val domain = intDomains[operand]
    val first = requireNotNull(integerPowerOrNull(domain.min, exponent)) { "Power domain overflows Long" }
    val last = requireNotNull(integerPowerOrNull(domain.max, exponent)) { "Power domain overflows Long" }
    val lower = if (exponent > 0 && exponent and 1 == 0 && domain.min <= 0 && domain.max >= 0) 0L else minOf(first, last)
    val upper = maxOf(first, last)
    val name = newAuxIntVar(IntDomain(checkedInt(lower) { "Power domain min" }.toLong(),
        checkedInt(upper) { "Power domain max" }.toLong()))
    factors += Power(operand, exponent, intVarOf(name))
    return IntRef(name)
}
