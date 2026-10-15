package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Power

internal fun FlatZincCompiler.emitIntPower(c: FznConstraint) {
    expectArity(c, 3)
    val exponent = evalIntConstOrNull(c.args[1])
        ?: unsupportedHere("int_pow requires a fixed nonnegative Int exponent")
    if (exponent < 0 || exponent > Int.MAX_VALUE) unsupportedHere("int_pow requires a fixed nonnegative Int exponent")
    factors.add(Power(resolveIntVar(c.args[0]), exponent.toInt(), resolveIntVar(c.args[2])))
}

internal fun FlatZincCompiler.emitFloatPower(c: FznConstraint) {
    expectArity(c, 3)
    val value = resolveFloatVarOrConst(c.args[1]) as? FloatRef.Const
        ?: unsupportedHere("float_pow requires a fixed nonnegative integer exponent")
    if (value.value < 0.0 || value.value > Int.MAX_VALUE.toDouble() || value.value != value.value.toInt().toDouble()) {
        unsupportedHere("float_pow requires a fixed nonnegative Int exponent")
    }
    fun coordinate(expression: FznExpr): Power.Coordinate.Real {
        val ref = resolveFloatVarOrConst(expression)
        val id = when (ref) {
            is FloatRef.Const -> allocFloat("__power_const_${realLo.size}", ref.value, ref.value, lpOnly = true)
            is FloatRef.Var -> {
                if (!ref.bk.lpOnly) unsupportedHere("float_pow requires exact continuous coordinates")
                ref.bk.varId
            }
        }
        return Power.Coordinate.Real(com.eignex.klause.ir.SourceRealId(id))
    }
    factors.add(Power(coordinate(c.args[0]), value.value.toInt(), coordinate(c.args[2])))
}
