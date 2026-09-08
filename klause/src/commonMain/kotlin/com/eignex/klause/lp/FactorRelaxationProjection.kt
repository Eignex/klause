package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.impliedLinearRows

/** Emit this factor's LP relaxation into [builder]. */
internal fun Factor.emitLpRelaxation(builder: RelaxationBuilder, linearProjection: LinearLpProjection? = null) {
    if (impliedLinearRows.isNotEmpty()) {
        for (row in impliedLinearRows) row.emitLpRelaxation(builder, linearProjection)
        return
    }
    when (this) {
        is ArrayMinMax -> emitLpRelaxation(builder)
        is Element -> emitLpRelaxation(builder)
        is GlobalCardinality -> emitLpRelaxation(builder)
        is Mdd -> emitLpRelaxation(builder)
        is NValue -> emitLpRelaxation(builder)
        is Product -> emitLpRelaxation(builder)
        is RealProduct -> emitLpRelaxation(builder)
        is Regular -> emitLpRelaxation(builder)
        is ReifiedCardinality -> emitLpRelaxation(builder)
        is ReifiedPseudoBoolean -> emitLpRelaxation(builder)
        is Table -> emitLpRelaxation(builder)
        else -> Unit
    }
}
