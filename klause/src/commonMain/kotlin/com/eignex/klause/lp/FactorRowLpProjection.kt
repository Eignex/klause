package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp

/*
 * Applies finite-LP policy to a factor's shared linear statement.
 *
 * The row extractor retains the factor's exact constants. This adapter is the deliberately separate
 * finite view: unconditional integer rows stay integral, wide rows are rounded outward, and an
 * activated integer row becomes its live big-M relaxation.
 */
internal fun FactorRow.emitLpRelaxation(
    builder: RelaxationBuilder,
    projection: LinearLpProjection? = null,
    source: Factor? = null,
) {
    when (this) {
        is FactorRow.Wide -> {
            if (activator != FactorRow.ALWAYS) return
            if (projection == null) {
                if (op == LinearOp.LE || op == LinearOp.EQ) emitWideLpRelaxation(builder)
            } else if (source == null) {
                emitWideLpRelaxation(builder)
            } else {
                projection.emitWide(source, this, builder)
            }
        }

        is FactorRow.Doubles -> when (activator) {
            FactorRow.ALWAYS -> emitUnconditionalLpRelaxation(builder)

            else -> if (integerCoeffs != null) {
                emitReifiedIntegerLpRelaxation(builder)
            } else {
                emitReifiedRealLpRelaxation(builder)
            }
        }
    }
}

private fun FactorRow.Doubles.emitUnconditionalLpRelaxation(builder: RelaxationBuilder) {
    val coeffs = integerCoeffs
    val exactBound = integerBound
    if (realVars.isEmpty() && coeffs != null && exactBound != null) {
        builder.linearRow(op, intVars, coeffs, exactBound)
        return
    }
    val columns = IntArray(intVars.size + realVars.size)
    val coefficients = DoubleArray(columns.size)
    for (i in intVars.indices) {
        columns[i] = builder.intColumn(intVars[i])
        coefficients[i] = intCoeffs[i]
    }
    for (j in realVars.indices) {
        val column = builder.realColumn(realVars[j])
        if (column < 0) return
        columns[intVars.size + j] = column
        coefficients[intVars.size + j] = realCoeffs[j]
    }
    builder.realRow(columns, coefficients, op, bound, strict)
}
