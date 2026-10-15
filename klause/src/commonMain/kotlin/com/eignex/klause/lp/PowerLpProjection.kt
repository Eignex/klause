package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.ir.LinearOp

internal fun Power.emitLpRelaxation(builder: RelaxationBuilder) {
    val operand = base as? Power.Coordinate.Real ?: return
    val output = result as Power.Coordinate.Real
    if (exponent > 1) return
    val variables = if (exponent == 0) intArrayOf(output.id.index) else
        intArrayOf(output.id.index, operand.id.index)
    val coefficients = if (exponent == 0) doubleArrayOf(1.0) else doubleArrayOf(1.0, -1.0)
    Linear(intArrayOf(), doubleArrayOf(), variables, coefficients, LinearOp.EQ,
        if (exponent == 0) 1.0 else 0.0).emitLpRelaxation(builder)
}
