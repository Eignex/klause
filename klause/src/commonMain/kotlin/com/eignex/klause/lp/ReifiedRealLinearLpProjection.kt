package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal fun FactorRow.Doubles.emitReifiedRealLpRelaxation(builder: RelaxationBuilder) {
    val pin = builder.liveBool(activator) ?: return
    val cols = IntArray(intVars.size + realVars.size)
    val coeffs = DoubleArray(cols.size)
    for (i in intVars.indices) {
        cols[i] = builder.intColumn(intVars[i])
        coeffs[i] = intCoeffs[i]
    }
    for (j in realVars.indices) {
        val c = builder.realColumn(realVars[j])
        if (c < 0) return
        cols[intVars.size + j] = c
        coeffs[intVars.size + j] = realCoeffs[j]
    }
    val premise = intArrayOf(Lit.make(activator, pin))
    if (pin) {
        builder.realRow(cols, coeffs, op, bound, strict, premise)
    } else {
        val flipped = if (op == LinearOp.LE) LinearOp.GE else LinearOp.LE
        builder.realRow(cols, coeffs, flipped, bound, !strict, premise)
    }
}
