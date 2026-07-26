package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.FlatZincCompiler.ParamValue
import com.eignex.klause.solver.Lit

internal fun FlatZincCompiler.evaluateParam(e: FznExpr, declaredType: FznType): ParamValue = when (e) {
    is FznExpr.BoolLit -> ParamValue.Bool(e.value)

    is FznExpr.IntLit -> ParamValue.Int(e.value)

    is FznExpr.FloatLit -> ParamValue.Float(e.value)

    is FznExpr.IntSetLit -> ParamValue.IntSet(e.values)

    is FznExpr.IntRangeLit -> ParamValue.IntSet((e.lo..e.hi).toList().toLongArray())

    is FznExpr.ArrayLit -> {
        val elem = (declaredType as? FznType.Array)?.element ?: FznType.IntAny
        val arr = compileParamArray("<inline>", elem, e)
        ParamValue.Array(arr)
    }

    is FznExpr.Ident -> params[e.name] ?: failHere("undefined parameter `${e.name}`")

    else -> failHere("unsupported parameter initializer: ${e::class.simpleName}")
}

internal fun FlatZincCompiler.compileParamArray(name: String, elem: FznType, lit: FznExpr.ArrayLit): FlatZincArray =
    when (elem) {
        FznType.Bool -> FlatZincArray.BoolParam(
            name,
            BooleanArray(lit.elements.size) {
                (lit.elements[it] as? FznExpr.BoolLit)?.value
                    ?: failHere("bool array `$name`: element ${it + 1} not a bool literal")
            },
        )

        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> {
            FlatZincArray.IntParam(
                name,
                LongArray(lit.elements.size) {
                    evalIntConst(lit.elements[it])
                },
            )
        }

        is FznType.FloatRange, FznType.FloatAny -> {
            FlatZincArray.FloatParam(
                name,
                DoubleArray(lit.elements.size) {
                    evalFloatConst(lit.elements[it])
                },
            )
        }

        is FznType.SetOfInt -> FlatZincArray.IntSetParam(
            name,
            lit.elements.map { e ->
                val arr: IntArray = when (e) {
                    is FznExpr.IntSetLit -> IntArray(e.values.size) { e.values[it].toInt() }

                    is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }

                    is FznExpr.Ident -> (params[e.name] as? ParamValue.IntSet)?.let { p ->
                        IntArray(p.values.size) { p.values[it].toInt() }
                    } ?: failHere("`${e.name}` is not an int-set parameter")

                    else -> failHere("set-of-int array `$name`: unexpected element ${e::class.simpleName}")
                }
                arr.also { it.sort() }
            },
        )

        is FznType.Array -> failHere("nested arrays not supported")
    }

internal fun FlatZincCompiler.evalIntConst(e: FznExpr): Long = when (e) {
    is FznExpr.IntLit -> e.value

    is FznExpr.BoolLit -> if (e.value) 1L else 0L

    is FznExpr.Ident -> when (val p = params[e.name]) {
        is ParamValue.Int -> p.value
        is ParamValue.Bool -> if (p.value) 1L else 0L
        null -> failHere("`${e.name}` is not a constant int")
        else -> failHere("`${e.name}` is not an int")
    }

    is FznExpr.ArrayAccess -> {
        val arr = arrays[e.name] as? FlatZincArray.IntParam
            ?: failHere("`${e.name}` is not an int parameter array")
        arr.values[arrayOffset(arr.values.size, e.index, e.name)]
    }

    else -> failHere("expected int constant, got ${e::class.simpleName}")
}

/** Validate a 1-based FlatZinc array [index] against [size] and return the 0-based offset, failing
 *  with a source-located diagnostic rather than an [IndexOutOfBoundsException] on a bad index. */
internal fun FlatZincCompiler.arrayOffset(size: Int, index: Int, name: String): Int {
    if (index < 1 || index > size) failHere("array `$name` index $index out of bounds (1..$size)")
    return index - 1
}

/** Nullable variant of [evalIntConst]. */
internal fun FlatZincCompiler.evalIntConstOrNull(e: FznExpr): Long? = when (e) {
    is FznExpr.IntLit -> e.value

    is FznExpr.BoolLit -> if (e.value) 1L else 0L

    is FznExpr.Ident -> when (val p = params[e.name]) {
        is ParamValue.Int -> p.value
        is ParamValue.Bool -> if (p.value) 1L else 0L
        else -> null
    }

    is FznExpr.ArrayAccess -> (arrays[e.name] as? FlatZincArray.IntParam)?.values?.getOrNull(e.index - 1)

    else -> null
}

internal fun FlatZincCompiler.evalFloatConst(e: FznExpr): Double = when (e) {
    is FznExpr.FloatLit -> e.value

    is FznExpr.IntLit -> e.value.toDouble()

    is FznExpr.Ident -> when (val p = params[e.name]) {
        is ParamValue.Float -> p.value
        is ParamValue.Int -> p.value.toDouble()
        null -> failHere("`${e.name}` is not a constant float")
        else -> failHere("`${e.name}` is not a float")
    }

    is FznExpr.ArrayAccess -> {
        when (val arr = arrays[e.name]) {
            is FlatZincArray.FloatParam -> arr.values[arrayOffset(arr.values.size, e.index, e.name)]
            is FlatZincArray.IntParam -> arr.values[arrayOffset(arr.values.size, e.index, e.name)].toDouble()
            else -> failHere("`${e.name}` is not a numeric parameter array")
        }
    }

    else -> failHere("expected float constant, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.evalIntConstArray(e: FznExpr): IntArray = when (e) {
    is FznExpr.ArrayLit -> IntArray(e.elements.size) { evalIntConst(e.elements[it]).toInt() }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.IntParam -> IntArray(arr.values.size) { arr.values[it].toInt() }
        else -> failHere("`${e.name}` is not an int parameter array")
    }

    else -> failHere("expected int array, got ${e::class.simpleName}")
}

/** [evalIntConstArray] without narrowing to `Int` — the linear-constraint coefficient path, where a
 *  coefficient may legitimately exceed 32-bit range (mirrors [evalIntConst] returning `Long`). */
internal fun FlatZincCompiler.evalIntConstArrayLong(e: FznExpr): LongArray = when (e) {
    is FznExpr.ArrayLit -> LongArray(e.elements.size) { evalIntConst(e.elements[it]) }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.IntParam -> arr.values.copyOf()
        else -> failHere("`${e.name}` is not an int parameter array")
    }

    else -> failHere("expected int array, got ${e::class.simpleName}")
}

/** Nullable variant of [evalIntConstArray]. */
internal fun FlatZincCompiler.tryEvalIntConstArray(e: FznExpr): IntArray? = when (e) {
    is FznExpr.ArrayLit -> {
        val out = IntArray(e.elements.size)
        var ok = true
        for (i in e.elements.indices) {
            val v = evalIntConstOrNull(e.elements[i]) ?: run {
                ok = false
                0L
            }
            out[i] = v.toInt()
            if (!ok) break
        }
        if (ok) out else null
    }

    is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.IntParam)?.values
        ?.let { v -> IntArray(v.size) { v[it].toInt() } }

    else -> null
}

/** Nullable variant of [evalIntConstArrayLong] — used where the values may exceed 32-bit range
 *  (scheduling durations / rectangle sizes) but the caller must still distinguish a constant array
 *  from a variable one. */
internal fun FlatZincCompiler.tryEvalIntConstArrayLong(e: FznExpr): LongArray? = when (e) {
    is FznExpr.ArrayLit -> {
        val out = LongArray(e.elements.size)
        var ok = true
        for (i in e.elements.indices) {
            val v = evalIntConstOrNull(e.elements[i]) ?: run {
                ok = false
                0L
            }
            out[i] = v
            if (!ok) break
        }
        if (ok) out else null
    }

    is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.IntParam)?.values?.copyOf()

    else -> null
}

internal fun FlatZincCompiler.evalFloatConstArray(e: FznExpr): DoubleArray = when (e) {
    is FznExpr.ArrayLit -> DoubleArray(e.elements.size) { evalFloatConst(e.elements[it]) }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.FloatParam -> arr.values
        is FlatZincArray.IntParam -> DoubleArray(arr.values.size) { arr.values[it].toDouble() }
        else -> failHere("`${e.name}` is not a float parameter array")
    }

    else -> failHere("expected float array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.evalBoolVarArray(e: FznExpr): IntArray = when (e) {
    is FznExpr.ArrayLit -> IntArray(e.elements.size) { resolveBoolLit(e.elements[it]) }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> {
            require(arr.elementKind == FlatZincArray.Vars.ElementKind.Bool) {
                "`${e.name}` is not a bool var array"
            }
            IntArray(arr.varIds.size) { Lit.make(arr.varIds[it], true) }
        }

        else -> failHere("`${e.name}` is not a bool var array")
    }

    else -> failHere("expected bool var array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.evalBoolConstArray(e: FznExpr): BooleanArray = when (e) {
    is FznExpr.ArrayLit -> BooleanArray(e.elements.size) {
        (e.elements[it] as? FznExpr.BoolLit)?.value
            ?: failHere("expected bool literal in const array, got ${e.elements[it]::class.simpleName}")
    }

    is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.BoolParam)?.values
        ?: failHere("`${e.name}` is not a bool parameter array")

    else -> failHere("expected bool const array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.evalIntVarArray(e: FznExpr): IntArray = when (e) {
    is FznExpr.ArrayLit -> IntArray(e.elements.size) { resolveIntVar(e.elements[it]) }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> {
            require(
                arr.elementKind == FlatZincArray.Vars.ElementKind.Int ||
                    arr.elementKind == FlatZincArray.Vars.ElementKind.Float,
            ) {
                "`${e.name}` is not an int var array"
            }
            arr.varIds.copyOf()
        }

        // A par int array handed to a var-array position (e.g. `count` over a constant array):
        // materialize each constant as a fixed singleton var so the global sees uniform var ids.
        is FlatZincArray.IntParam -> IntArray(arr.values.size) {
            val v = arr.values[it]
            allocInt("__const_int_${v}_${intVars.size}", v, v)
        }

        else -> failHere("`${e.name}` is not an int var array")
    }

    else -> failHere("expected int var array, got ${e::class.simpleName}")
}

/** Resolve a bool reference to a solver literal. */
internal fun FlatZincCompiler.resolveBoolLit(e: FznExpr): Int = when (e) {
    is FznExpr.Ident -> {
        val id = boolVars[e.name] ?: failHere("undefined bool var `${e.name}`")
        Lit.make(id, true)
    }

    is FznExpr.BoolLit -> {
        val name = "__const_${if (e.value) "T" else "F"}_${boolVars.size}"
        val id = allocBool(name)
        factors.add(Clause(intArrayOf(Lit.make(id, e.value))))
        Lit.make(id, true)
    }

    is FznExpr.ArrayAccess -> {
        val arr = arrays[e.name] as? FlatZincArray.Vars
            ?: failHere("`${e.name}` is not a var array")
        require(arr.elementKind == FlatZincArray.Vars.ElementKind.Bool) {
            "`${e.name}` is not a bool var array"
        }
        Lit.make(arr.varIds[arrayOffset(arr.varIds.size, e.index, e.name)], true)
    }

    else -> failHere("expected bool var or literal, got ${e::class.simpleName}")
}

/** Resolve an int reference into a solver int var id. */
internal fun FlatZincCompiler.resolveIntVar(e: FznExpr): Int = when (e) {
    is FznExpr.Ident -> intVars[e.name] ?: failHere("undefined int var `${e.name}`")

    is FznExpr.IntLit -> {
        val name = "__const_int_${e.value}_${intVars.size}"
        allocInt(name, e.value, e.value)
    }

    is FznExpr.ArrayAccess -> {
        val arr = arrays[e.name] as? FlatZincArray.Vars
            ?: failHere("`${e.name}` is not a var array")
        arr.varIds[arrayOffset(arr.varIds.size, e.index, e.name)]
    }

    else -> failHere("expected int var, got ${e::class.simpleName}")
}

/** Resolve an array-initializer reference to a var id. */
internal fun FlatZincCompiler.resolveVarRef(e: FznExpr, declaredElement: FznType): Int = when (declaredElement) {
    FznType.Bool -> {
        val lit = resolveBoolLit(e)
        Lit.variable(lit)
    }

    is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> resolveIntVar(e)

    is FznType.FloatRange, FznType.FloatAny -> when (e) {
        is FznExpr.Ident -> intVars[e.name] ?: failHere("undefined float var `${e.name}`")
        else -> failHere("expected float var, got ${e::class.simpleName}")
    }

    is FznType.SetOfInt -> failHere("set-of-int element refs not supported")

    is FznType.Array -> failHere("nested arrays not supported")
}

internal fun FlatZincCompiler.nameOfBoundVar(e: FznExpr): String = (e as? FznExpr.Ident)?.name
    ?: failHere("expected a var name, got ${e::class.simpleName}")
