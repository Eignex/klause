package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.RealElement

internal fun FlatZincCompiler.emitFloatSelection(c: FznConstraint) {
    expectArity(c, 3)
    val idx = resolveIntVar(c.args[0])
    val arr = evalFloatConstArray(c.args[1])
    val xRef = resolveFloatVarOrConst(c.args[2])
    val x = when (xRef) {
        is FloatRef.Var -> xRef.bk
        is FloatRef.Const -> resolveFloatElement(c.args[2], "__float_element_${floatVars.size}")
    }

    factors.add(RealElement(idx, x.varId, arr))
    val domain = intDomains[idx]
    if (x.varId in finiteFloatChoices && domain.min == domain.max &&
        domain.min in 1L..arr.size.toLong()
    ) {
        factors.add(Clause(intArrayOf(exactFloatValueLiteral(x.varId, arr[(domain.min - 1).toInt()]))))
    }
}

internal fun FlatZincCompiler.emitRealMinMax(c: FznConstraint, max: Boolean) {
    val operands = c.args.map { resolveFloatElement(it, "__real_extremum_${floatVars.size}").varId }
    factors.add(RealArrayMinMax(operands[2], intArrayOf(operands[0], operands[1]), max))
}
