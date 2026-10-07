package com.eignex.klause.formats.flatzinc

import com.eignex.klause.lowering.FloatBucketing

internal data class FiniteFloatDomain(val constraintIndex: Int, val variable: FloatBucketing, val values: DoubleArray)

internal fun FlatZincCompiler.collectFiniteFloatDomains(): List<FiniteFloatDomain> {
    val equalities = HashMap<Int, Pair<FloatBucketing, Double>>()
    for (c in model.constraints) {
        if (c.name != "float_eq_reif" || c.args.size != 3) continue
        locateConstraint(c)
        val guard = boolVariable(c.args[2]) ?: continue
        val a = resolveFloatVarOrConst(c.args[0])
        val b = resolveFloatVarOrConst(c.args[1])
        val choice = when {
            a is FloatRef.Var && b is FloatRef.Const -> a.bk to b.value
            b is FloatRef.Var && a is FloatRef.Const -> b.bk to a.value
            else -> continue
        }
        equalities[guard] = choice
    }
    return model.constraints.mapIndexedNotNull { index, c ->
        locateConstraint(c)
        if (c.name == "int2float" && c.args.size == 2) {
            val variable = (resolveFloatVarOrConst(c.args[1]) as? FloatRef.Var)?.bk
                ?: return@mapIndexedNotNull null
            val source = resolveIntVar(c.args[0])
            if (variable.lpOnly) integerFloatSources[variable.varId] = source
            val domain = intDomains[source]
            // Every integer in this range has an exact binary64 representation.
            if (domain.min < -9007199254740992L || domain.max > 9007199254740992L) {
                return@mapIndexedNotNull null
            }
            val values = domain.spanOrNull(floatChoiceLimit.toLong()) ?: return@mapIndexedNotNull null
            return@mapIndexedNotNull FiniteFloatDomain(
                index,
                variable,
                DoubleArray(values.size) { values.valueAt(it).toDouble() },
            )
        }
        if (c.name == "array_float_element" && c.args.size == 3) {
            val variable = (resolveFloatVarOrConst(c.args[2]) as? FloatRef.Var)?.bk
                ?: return@mapIndexedNotNull null
            return@mapIndexedNotNull FiniteFloatDomain(index, variable, evalFloatConstArray(c.args[1]))
        }
        val guards = when {
            c.name == "bool_clause" && c.args.size == 2 && boolVariables(c.args[1])?.isEmpty() == true ->
                boolVariables(c.args[0])

            c.name == "array_bool_or" && c.args.size == 2 && c.args[1] == FznExpr.BoolLit(true) ->
                boolVariables(c.args[0])

            else -> null
        } ?: return@mapIndexedNotNull null
        val choices = guards.map { equalities[it] ?: return@mapIndexedNotNull null }
        val variable = choices.firstOrNull()?.first ?: return@mapIndexedNotNull null
        // Every disjunct must imply an equality on the same float column.
        if (choices.any { it.first.varId != variable.varId }) return@mapIndexedNotNull null
        FiniteFloatDomain(index, variable, choices.map { it.second }.toDoubleArray())
    }
}

private fun FlatZincCompiler.boolVariable(e: FznExpr): Int? = when (e) {
    is FznExpr.Ident -> boolVars[e.name]

    is FznExpr.ArrayAccess -> (arrays[e.name] as? FlatZincArray.Vars)
        ?.takeIf { it.elementKind == FlatZincArray.Vars.ElementKind.Bool }
        ?.varIds?.getOrNull(e.index - 1)

    else -> null
}

private fun FlatZincCompiler.boolVariables(e: FznExpr): IntArray? {
    return when (e) {
        is FznExpr.ArrayLit -> e.elements.map { boolVariable(it) ?: return null }.toIntArray()

        is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.Vars)
            ?.takeIf { it.elementKind == FlatZincArray.Vars.ElementKind.Bool }?.varIds

        else -> null
    }
}
