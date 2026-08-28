package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal fun ReifiedRealLinear.emitLpRelaxation(builder: RelaxationBuilder) {
    val pin = builder.liveBool(aux) ?: return
    val cols = IntArray(vars.size + realVars.size)
    val coeffs = DoubleArray(cols.size)
    for (i in vars.indices) {
        cols[i] = builder.intColumn(vars[i])
        coeffs[i] = intCoeffs[i]
    }
    for (j in realVars.indices) {
        val c = builder.realColumn(realVars[j])
        if (c < 0) return
        cols[vars.size + j] = c
        coeffs[vars.size + j] = realCoeffs[j]
    }
    val premise = intArrayOf(Lit.make(aux, pin))
    if (pin) {
        builder.realRow(cols, coeffs, op, bound, strict, premise)
    } else {
        val flipped = if (op == LinearOp.LE) LinearOp.GE else LinearOp.LE
        builder.realRow(cols, coeffs, flipped, bound, !strict, premise)
    }
}
