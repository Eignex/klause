package com.eignex.klause.lp

import com.eignex.klause.factor.table.RealChoice
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal fun RealChoice.emitLpRelaxation(builder: RelaxationBuilder) {
    val value = builder.liveBool(Lit.variable(condition)) ?: return
    val active = Lit.evaluate(condition, value)
    val selected = if (active) whenTrue else whenFalse
    val output = builder.realColumn(result)
    val input = builder.realColumn(selected)
    if (output < 0 || input < 0) return
    val premise = if (active) condition else Lit.negate(condition)
    builder.realRow(intArrayOf(output, input), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0,
        premiseLits = intArrayOf(premise))
}
