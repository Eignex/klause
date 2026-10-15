package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun ExactLinear.finiteRelaxationSupported(): Boolean = relation != LinearOp.NE &&
    constants.exactBound.fitsLong() &&
    constants.exactBound.toLongExact() != Long.MIN_VALUE &&
    (0 until size).all {
        constants.exactCoeff(it).fitsLong() && constants.exactCoeff(it).toLongExact() != Long.MIN_VALUE
    }

internal fun ExactLinear.emitExactLpRelaxation(builder: RelaxationBuilder, indicator: Int = -1, value: Long = 0L) {
    if (relation == LinearOp.NE) return
    if (!finiteRelaxationSupported()) {
        if (indicator < 0 && variables.reals.isEmpty()) emitWideLpRelaxation(builder)
        return
    }
    val columns = IntArray(size) { index ->
        val reference = ref(index)
        if (Term.isInt(reference)) builder.intColumn(Term.intVar(reference))
        else builder.realColumn(Term.realVar(reference)).also { if (it < 0) return }
    }
    val coefficients = LongArray(size) { constants.exactCoeff(it).toLongExact() }
    if (indicator < 0) builder.row(columns, coefficients, relation, constants.exactBound.toLongExact())
    else builder.conditionedRow(columns, coefficients, relation, constants.exactBound.toLongExact(), indicator, value)
}

internal fun LinearIndicator.emitExactLpRelaxation(builder: RelaxationBuilder) {
    val trigger = if (whenOne) 1L else 0L
    val domain = builder.liveDomain(indicator)
    if (domain.min != trigger || domain.max != trigger) return
    row.emitExactLpRelaxation(builder, indicator, trigger)
}
