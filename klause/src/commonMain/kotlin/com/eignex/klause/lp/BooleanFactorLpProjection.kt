package com.eignex.klause.lp

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.LinearOp

internal fun Clause.emitLpRelaxation(builder: RelaxationBuilder) {
    builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = 1L)
}

internal fun Cardinality.emitLpRelaxation(builder: RelaxationBuilder) {
    builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = min.toLong())
    builder.boolRow(literals, weights = null, op = LinearOp.LE, bound = max.toLong())
}

internal fun PseudoBoolean.emitLpRelaxation(builder: RelaxationBuilder) {
    builder.boolRow(literals, weights, relation, bound)
}
