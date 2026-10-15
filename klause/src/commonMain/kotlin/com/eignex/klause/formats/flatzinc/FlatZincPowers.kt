package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
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
    val base = resolveFloatVarOrConst(c.args[0])
    val result = resolveFloatVarOrConst(c.args[2])
    if (listOf(base, result).filterIsInstance<FloatRef.Var>().any { !it.bk.lpOnly }) {
        fun grid(ref: FloatRef): GridCoordinate = when (ref) {
            is FloatRef.Var -> {
                if (ref.bk.lpOnly) unsupportedHere("float_pow cannot mix exact and grid coordinates")
                GridCoordinate(ref.bk.varId, ref.bk.lo, ref.bk.hi, ref.bk.buckets)
            }
            is FloatRef.Const -> resolveFloatElement(FznExpr.FloatLit(ref.value),
                "__grid_power_${floatVars.size}", lpOnly = false).let {
                GridCoordinate(it.varId, it.lo, it.hi, it.buckets)
            }
        }
        factors.add(GridPower(grid(base), value.value.toInt(), grid(result)))
        return
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
