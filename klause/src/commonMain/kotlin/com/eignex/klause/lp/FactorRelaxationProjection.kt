package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.linearRow
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.GlobalCardinality
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
internal fun Factor.emitLpRelaxation(builder: RelaxationBuilder, linearProjection: LinearLpProjection? = null) {
    linearRow()?.let {
        it.emitLpRelaxation(builder, linearProjection, this)
        return
    }
    when (this) {
        is ArrayMinMax -> emitLpRelaxation(builder)
        is Cardinality -> emitLpRelaxation(builder)
        is Clause -> emitLpRelaxation(builder)
        is Element -> emitLpRelaxation(builder)
        is GlobalCardinality -> emitLpRelaxation(builder)
        is Mdd -> emitLpRelaxation(builder)
        is NValue -> emitLpRelaxation(builder)
        is Product -> emitLpRelaxation(builder)
        is PseudoBoolean -> emitLpRelaxation(builder)
        is RealProduct -> emitLpRelaxation(builder)
        is Regular -> emitLpRelaxation(builder)
        is ReifiedCardinality -> emitLpRelaxation(builder)
        is ReifiedPseudoBoolean -> emitLpRelaxation(builder)
        is Table -> emitLpRelaxation(builder)
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
