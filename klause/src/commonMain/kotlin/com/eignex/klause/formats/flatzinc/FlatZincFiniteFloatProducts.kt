package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.reifyRealLinear
import com.eignex.klause.lowering.tseitinOr

internal fun FlatZincCompiler.emitFiniteFloatDomains() {
    for ((variable, values) in finiteFloatChoices) {
        val guards = values.map { exactFloatValueLiteral(variable, it) }.distinct().toIntArray()
        if (guards.isEmpty()) {
            postFalseFactor()
            continue
        }
        factors.add(Clause(guards))
        if (guards.size <= 4) {
            for (i in guards.indices) {
                for (j in i + 1 until guards.size) {
                    factors.add(Clause(intArrayOf(Lit.negate(guards[i]), Lit.negate(guards[j]))))
                }
            }
        } else {
            var prefix = guards[0]
            for (i in 1 until guards.size) {
                factors.add(Clause(intArrayOf(Lit.negate(prefix), Lit.negate(guards[i]))))
                if (i < guards.lastIndex) prefix = tseitinOr(listOf(prefix, guards[i]))
            }
        }
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
    val aValues = finiteChoices(a)
    val bValues = finiteChoices(b)
    val selectA = aValues != null && (bValues == null || aValues.size <= bValues.size)
    val values = (if (selectA) aValues else bValues)
        ?: unsupportedHere(
            "`float_times` is unsupported by exact float lowering without finite constant choices",
        )
    if (values.isEmpty()) {
        postFalseFactor()
        return
    }
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
