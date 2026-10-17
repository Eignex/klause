package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.reifyRealLinear

internal fun FlatZincCompiler.emitFiniteFloatDomains() {
    for ((variable, values) in finiteFloatChoices) {
        factors.add(RealFiniteDomain(variable, values))
    }
}

internal fun FlatZincCompiler.recordFiniteFloatChoices(domain: FiniteFloatDomain) {
    if (!domain.variable.lpOnly) return
    val values = domain.values.distinct().toDoubleArray()
    val existing = finiteFloatChoices[domain.variable.varId]
    finiteFloatChoices[domain.variable.varId] = existing?.filter { candidate -> values.any { it == candidate } }
        ?.toDoubleArray() ?: values
}

internal fun FlatZincCompiler.emitFiniteFloatProduct(a: FloatRef, b: FloatRef, result: FloatRef) {
    fun coordinate(ref: FloatRef): FloatBucketing = when (ref) {
        is FloatRef.Var -> ref.bk.takeIf { it.lpOnly }
            ?: unsupportedHere("`float_times` cannot mix exact real and grid coordinates")
        is FloatRef.Const -> resolveFloatElement(FznExpr.FloatLit(ref.value),
            "__finite_product_${floatVars.size}", lpOnly = true)
    }
    val first = FloatRef.Var(coordinate(a))
    val second = FloatRef.Var(coordinate(b))
    val output = coordinate(result).varId
    val aValues = finiteChoices(first)
    val bValues = finiteChoices(second)
    val selectA = aValues != null && (bValues == null || aValues.size <= bValues.size)
    val values = if (selectA) aValues else bValues
    if (values == null) {
        factors.add(ContinuousProduct(first.bk.varId, second.bk.varId, output))
        return
    }
    val selected = if (selectA) first else second
    factors.add(ContinuousProduct(first.bk.varId, second.bk.varId, output, selected.bk.varId, values))
}

internal fun FlatZincCompiler.exactFloatValueLiteral(variable: Int, value: Double): Int {
    val canonical = if (value == 0.0) 0.0 else value
    return floatValueLiterals.getOrPut(variable to canonical) {
        if (variable in affineFloatImages) {
            val literal = Lit.make(newBool(), true)
            postExactFloatLinear(
                doubleArrayOf(1.0),
                intArrayOf(variable),
                LinearOp.EQ,
                canonical,
                reifier = literal,
            )
            return@getOrPut literal
        }
        val integer = integerFloatSources[variable]
        if (integer != null && canonical >= -9007199254740992.0 && canonical <= 9007199254740992.0 &&
            canonical.toLong().toDouble() == canonical
        ) {
            reifyLinear(longArrayOf(1L), intArrayOf(integer), LinearOp.EQ, canonical.toLong())
        } else {
            reifyRealLinear(doubleArrayOf(1.0), intArrayOf(variable), LinearOp.EQ, canonical)
        }
    }
}

private fun FlatZincCompiler.finiteChoices(ref: FloatRef): DoubleArray? {
    val variable = (ref as? FloatRef.Var)?.bk ?: return null
    return if (variable.lo == variable.hi) doubleArrayOf(variable.lo) else finiteFloatChoices[variable.varId]
}
