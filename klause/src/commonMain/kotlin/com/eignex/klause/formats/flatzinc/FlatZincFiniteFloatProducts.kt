package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyRealLinear

internal fun FlatZincCompiler.recordFiniteFloatChoices(c: FznConstraint) {
    if (c.name != "array_float_element") return
    expectArity(c, 3)
    val result = resolveFloatVarOrConst(c.args[2]) as? FloatRef.Var ?: return
    if (!result.bk.lpOnly) return
    val values = evalFloatConstArray(c.args[1]).distinct().toDoubleArray()
    val existing = finiteFloatChoices[result.bk.varId]
    finiteFloatChoices[result.bk.varId] = existing?.filter { candidate -> values.any { it == candidate } }
        ?.toDoubleArray() ?: values
}

internal fun FlatZincCompiler.emitFiniteFloatProduct(a: FloatRef, b: FloatRef, result: FloatRef) {
    val aValues = finiteChoices(a)
    val bValues = finiteChoices(b)
    val selectA = aValues != null && (bValues == null || aValues.size <= bValues.size)
    val values = (if (selectA) aValues else bValues)
        ?: unsupportedHere(
            "`float_times` is unsupported by exact float lowering without finite constant choices",
        )
    val selected = (if (selectA) a else b) as FloatRef.Var
    val other = (if (selectA) b else a) as FloatRef.Var
    val guards = IntArray(values.size)
    for ((i, value) in values.withIndex()) {
        val guard = exactFloatValueLiteral(selected.bk.varId, value)
        guards[i] = guard
        val product = when (result) {
            is FloatRef.Const -> reifyRealLinear(
                doubleArrayOf(value),
                intArrayOf(other.bk.varId),
                LinearOp.EQ,
                result.value,
            )

            is FloatRef.Var -> reifyRealLinear(
                doubleArrayOf(value, -1.0),
                intArrayOf(other.bk.varId, result.bk.varId),
                LinearOp.EQ,
                0.0,
            )
        }
        factors.add(Clause(intArrayOf(Lit.negate(guard), product)))
    }
    factors.add(Clause(guards))
}

internal fun FlatZincCompiler.exactFloatValueLiteral(variable: Int, value: Double): Int =
    floatValueLiterals.getOrPut(variable to value) {
        reifyRealLinear(doubleArrayOf(1.0), intArrayOf(variable), LinearOp.EQ, value)
    }

private fun FlatZincCompiler.finiteChoices(ref: FloatRef): DoubleArray? {
    val variable = (ref as? FloatRef.Var)?.bk ?: return null
    return if (variable.lo == variable.hi) doubleArrayOf(variable.lo) else finiteFloatChoices[variable.varId]
}
