package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.impliedLinearRows

internal fun Factor.emitLpRelaxation(
    builder: RelaxationBuilder,
    linearProjection: LinearLpProjection? = null,
    provider: RelaxationProjectionProvider = BuiltInRelaxationProjectionProvider,
) {
    if (provider === BuiltInRelaxationProjectionProvider) {
        BuiltInRelaxationProjectionProvider.emit(this, builder, linearProjection)
    } else {
        provider.select(this)?.emit(this, builder, linearProjection)
    }
}

internal fun Factor.buildLpRelaxation(builder: RelaxationBuilder, linearProjection: LinearLpProjection?) {
    if (this is ExactLinear) {
        emitExactLpRelaxation(builder)
        return
    }
    if (this is LinearIndicator) {
        emitExactLpRelaxation(builder)
        return
    }
    if (this is ReifiedPseudoBoolean) {
        emitLpRelaxation(builder)
        return
    }
    if (impliedLinearRows.isNotEmpty()) {
        val slack = when (this) {
            is RealAbs -> builder.realResidualSlack(result, builder.realMagnitude(operand))
            is RealArrayMinMax -> builder.realResidualSlack(result, xs.maxOf { builder.realMagnitude(it) })
            else -> null
        }
        for (row in impliedLinearRows) row.emitLpRelaxation(builder, linearProjection, slack)
        return
    }
    when (this) {
        is ArrayMinMax -> emitLpRelaxation(builder)
        is ContinuousProduct -> emitLpRelaxation(builder)
        is Element -> emitLpRelaxation(builder)
        is RealElement -> emitLpRelaxation(builder)
        is GlobalCardinality -> emitLpRelaxation(builder)
        is Mdd -> emitLpRelaxation(builder)
        is NValue -> emitLpRelaxation(builder)
        is Product -> emitLpRelaxation(builder)
        is Power -> emitLpRelaxation(builder)
        is RealProduct -> emitLpRelaxation(builder)
        is Regular -> emitLpRelaxation(builder)
        is ReifiedCardinality -> emitLpRelaxation(builder)
        is Table -> emitLpRelaxation(builder)
        else -> Unit
    }
}
