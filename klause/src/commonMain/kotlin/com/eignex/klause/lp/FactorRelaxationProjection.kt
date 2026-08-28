package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term

/** Emit this factor's LP relaxation into [builder]. */
internal fun Factor.emitLpRelaxation(builder: RelaxationBuilder, factorId: Int) {
    when (this) {
        is ArrayMinMax -> emitLpRelaxation(builder, factorId)
        is Cardinality -> emitLpRelaxation(builder, factorId)
        is Clause -> emitLpRelaxation(builder, factorId)
        is Element -> emitLpRelaxation(builder, factorId)
        is GlobalCardinality -> emitLpRelaxation(builder, factorId)
        is Increasing -> emitLpRelaxation(builder, factorId)
        is Linear -> emitLpRelaxation(builder, factorId)
        is Mdd -> emitLpRelaxation(builder, factorId)
        is NValue -> emitLpRelaxation(builder, factorId)
        is Product -> emitLpRelaxation(builder, factorId)
        is PseudoBoolean -> emitLpRelaxation(builder, factorId)
        is RealProduct -> emitLpRelaxation(builder, factorId)
        is Regular -> emitLpRelaxation(builder, factorId)
        is ReifiedCardinality -> emitLpRelaxation(builder, factorId)
        is ReifiedLinear -> emitLpRelaxation(builder, factorId)
        is ReifiedPseudoBoolean -> emitLpRelaxation(builder, factorId)
        is ReifiedRealLinear -> emitLpRelaxation(builder, factorId)
        is Table -> emitLpRelaxation(builder, factorId)
        else -> for (row in linearRows) builder.emitExactRow(row)
    }
}

private fun RelaxationBuilder.emitExactRow(row: LinearRow) {
    val columns = IntArray(row.size)
    val coeffs = LongArray(row.size)
    var rhs = row.bound
    for (k in 0 until row.size) {
        val ref = row.ref(k)
        val c = row.coeff(k)
        if (Term.isBool(ref)) {
            val lit = Term.lit(ref)
            columns[k] = boolColumn(Lit.variable(lit))
            if (Lit.isPositive(lit)) {
                coeffs[k] = c
            } else {
                coeffs[k] = -c
                rhs -= c
            }
        } else {
            columns[k] = intColumn(Term.intVar(ref))
            coeffs[k] = c
        }
    }
    row(columns, coeffs, row.relation, rhs)
}
