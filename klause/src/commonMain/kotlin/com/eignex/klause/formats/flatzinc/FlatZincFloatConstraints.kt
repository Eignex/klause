package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.*
import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.reifyRealLinear
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.subExact
import kotlin.math.*

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 4 else 3)
    val varRefsAll = evalFloatVarArray(c.args[1])
    if (varRefsAll.all { it.lpOnly }) {
        // Real columns retain the source coefficients; scaled bucket arithmetic would lose precision.
        val coefs = evalFloatConstArray(c.args[0])
        if (coefs.size != varRefsAll.size) {
            failHere(
                "float linear coefficient and variable arrays have different lengths",
            )
        }
        val varRefs = varRefsAll
        val op = when (c.name.removeSuffix("_reif")) {
            "float_lin_eq" -> LinearOp.EQ
            "float_lin_ne" -> LinearOp.NE
            else -> LinearOp.LE
        }
        postExactFloatLinear(
            coefs,
            IntArray(coefs.size) { varRefs[it].varId },
            op,
            evalFloatConst(c.args[2]),
            reifier = if (reified) resolveBoolLit(c.args[3]) else null,
        )
        return
    }
    val scaled = resolveScaledFloatLinear(c, reified, varRefsAll)
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_le" -> LinearOp.LE
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled float linear ${c.name}")
    }
    postLinear(scaled.coeffs, scaled.vars, op, scaled.bound, if (reified) resolveBoolLit(c.args[3]) else null)
}

/** Lower `int2float` as a scaled linear equality on bucket indices. */
internal fun FlatZincCompiler.emitInt2Float(c: FznConstraint) {
    expectArity(c, 2)
    val xInt = resolveIntVar(c.args[0])
    val yName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("int2float: second arg must be a float var identifier")
    val yBk = floatVars[yName] ?: failHere("`$yName` is not a float var")
    if (yBk.lpOnly) {
        // y (real) = x (int): the mixed row 1·x − 1·y = 0.
        factors.add(
            Linear(intArrayOf(xInt), doubleArrayOf(1.0), intArrayOf(yBk.varId), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
        )
        return
    }
    val step = if (yBk.buckets > 1) (yBk.hi - yBk.lo) / (yBk.buckets - 1) else 0.0
    val cX = floatScale
    val cIdxY = scaledFloat(-step)
    val bound = scaledFloat(yBk.lo)
    factors.add(
        Linear(
            longArrayOf(cX, cIdxY),
            intArrayOf(xInt, yBk.varId),
            LinearOp.EQ,
            bound,
        ),
    )
}

internal fun FlatZincCompiler.resolveFloatVarOrConst(e: FznExpr): FloatRef = when (e) {
    is FznExpr.FloatLit -> FloatRef.Const(e.value)

    is FznExpr.IntLit -> FloatRef.Const(exactFloatInteger(e.value))

    is FznExpr.Ident -> floatVars[e.name]?.let { FloatRef.Var(it) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Float)?.let { FloatRef.Const(it.value) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Int)?.let { FloatRef.Const(exactFloatInteger(it.value)) }
        ?: failHere("`${e.name}` is not a float var or float param")

    is FznExpr.ArrayAccess -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> {
            val bucketings = arr.floatBucketings ?: failHere("`${e.name}` is not a float var array")
            FloatRef.Var(bucketings[arrayOffset(bucketings.size, e.index, e.name)])
        }

        is FlatZincArray.FloatParam -> FloatRef.Const(arr.values[arrayOffset(arr.length, e.index, e.name)])

        is FlatZincArray.IntParam -> FloatRef.Const(exactFloatInteger(arr.values[arrayOffset(arr.length, e.index, e.name)]))

        else -> failHere("`${e.name}` is not a float array")
    }

    else -> failHere("expected float var or float constant, got ${e::class.simpleName}")
}

internal sealed interface FloatRef {
    data class Var(val bk: FloatBucketing) : FloatRef
    data class Const(val value: Double) : FloatRef
}

internal fun FlatZincCompiler.resolveFloatElement(
    e: FznExpr,
    name: String,
    lpOnly: Boolean = name in lpOnlyFloats,
): FloatBucketing = when (val ref = resolveFloatVarOrConst(e)) {
    is FloatRef.Var -> ref.bk

    is FloatRef.Const -> {
        allocFloat(name, ref.value, ref.value, lpOnly)
        floatVars.getValue(name)
    }
}

/** Lower float comparisons on bucket indices. */
internal fun FlatZincCompiler.emitFloatBinaryCmp(c: FznConstraint, op: LinearOp, strict: Boolean, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
    val a = resolveFloatVarOrConst(c.args[0])
    val b = resolveFloatVarOrConst(c.args[1])
    when {
        a is FloatRef.Const && b is FloatRef.Const -> {
            val holds = when (op) {
                LinearOp.LE -> if (strict) a.value < b.value else a.value <= b.value
                LinearOp.GE -> if (strict) a.value > b.value else a.value >= b.value
                LinearOp.EQ -> a.value == b.value
                LinearOp.NE -> a.value != b.value
            }
            if (reified) {
                val r = resolveBoolLit(c.args[2])
                factors.add(
                    Clause(
                        intArrayOf(
                            if (holds) r else Lit.negate(r),
                        ),
                    ),
                )
            } else if (!holds) {
                // Two exact float constants that violate their relation: an exact contradiction.
                postFalseFactor()
            }
            return
        }

        else -> Unit
    }
    val varLpOnly = (a as? FloatRef.Var)?.bk?.lpOnly == true || (b as? FloatRef.Var)?.bk?.lpOnly == true
    if (varLpOnly) {
        // `a OP b` ⟺ `(a − b) OP 0`: real coefficients on the var operands, constants moved to the bound.
        val rv = IntArrayList()
        val rc = ArrayList<Double>()
        var bound = 0.0
        for ((ref, sign) in listOf(a to 1.0, b to -1.0)) {
            when (ref) {
                is FloatRef.Var -> {
                    rv.add(ref.bk.varId)
                    rc.add(sign)
                }

                is FloatRef.Const -> bound -= sign * ref.value
            }
        }
        postExactFloatLinear(
            rc.toDoubleArray(),
            rv.toIntArray(),
            op,
            bound,
            strict,
            if (reified) resolveBoolLit(c.args[2]) else null,
        )
        return
    }
    val varSide = if (a is FloatRef.Var) a.bk else (b as FloatRef.Var).bk
    val sign = if (a is FloatRef.Var) 1.0 else -1.0 // coefficient on the var-side arg
    val constPart = if (a is FloatRef.Var) {
        if (b is FloatRef.Const) b.value else 0.0
    } else {
        (a as FloatRef.Const).value
    }
    val step = if (varSide.buckets > 1) (varSide.hi - varSide.lo) / (varSide.buckets - 1) else 0.0
    val coefVar = scaledFloat(sign * step)
    // value(var) = lo + step·bucket, so `a OP b` with one constant is coefVar·bucket OP
    // sign·(const − lo)·scale (the var-var branch below overrides this bound).
    var scaledBound = subtractScaledFloat(scaledFloat(sign * constPart), scaledFloat(sign * varSide.lo))
    val coeffs: LongArray
    val vars: IntArray
    if (a is FloatRef.Var && b is FloatRef.Var) {
        val stepB = if (b.bk.buckets > 1) (b.bk.hi - b.bk.lo) / (b.bk.buckets - 1) else 0.0
        coeffs = longArrayOf(coefVar, scaledFloat(-stepB))
        vars = intArrayOf(varSide.varId, b.bk.varId)
        scaledBound = subtractScaledFloat(scaledFloat(b.bk.lo), scaledFloat(varSide.lo))
    } else {
        coeffs = longArrayOf(coefVar)
        vars = intArrayOf(varSide.varId)
    }
    val finalBound = if (op == LinearOp.LE && strict) subtractScaledFloat(scaledBound, 1L) else scaledBound
    postLinear(coeffs, vars, op, finalBound, if (reified) resolveBoolLit(c.args[2]) else null)
}

/** Strict float linear compare lowered to `<= bound - 1` in scaled space. */
internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 4 else 3)
    val variables = evalFloatVarArray(c.args[1])
    if (variables.all { it.lpOnly }) {
        val coefficients = evalFloatConstArray(c.args[0])
        if (coefficients.size != variables.size) {
            failHere("float linear coefficient and variable arrays differ in length")
        }
        postExactFloatLinear(
            coefficients,
            IntArray(variables.size) { variables[it].varId },
            LinearOp.LE,
            evalFloatConst(c.args[2]),
            strict = true,
            reifier = if (reified) resolveBoolLit(c.args[3]) else null,
        )
        return
    }
    val scaled = resolveScaledFloatLinear(c, reified, variables)
    val strictBound = subtractScaledFloat(scaled.bound, 1L)
    postLinear(
        scaled.coeffs,
        scaled.vars,
        LinearOp.LE,
        strictBound,
        if (reified) resolveBoolLit(c.args[3]) else null,
    )
}

internal fun FlatZincCompiler.emitFloatMinMax(c: FznConstraint, max: Boolean) {
    expectArity(c, 3)
    if (emitRealMinMax(c, max) || emitGridMinMax(c, max)) return
    val argA = c.args[0]
    val argB = c.args[1]
    val argC = c.args[2]
    fun emitIneq(left: FznExpr, right: FznExpr) {
        val fc = FznConstraint("float_le", listOf(left, right), emptyList())
        emitFloatBinaryCmp(fc, op = LinearOp.LE, strict = false, reified = false)
    }
    if (max) {
        emitIneq(argA, argC)
        emitIneq(argB, argC)
    } else {
        emitIneq(argC, argA)
        emitIneq(argC, argB)
    }
    // Keep aux names unique across multiple min/max constraints.
    val suffix = factors.size.toString()
    val auxA = allocBool("__fminmax_a_$suffix")
    val auxB = allocBool("__fminmax_b_$suffix")
    val eqA = FznConstraint("float_eq_reif", listOf(argA, argC, FznExpr.Ident("__fminmax_a_$suffix")), emptyList())
    val eqB = FznConstraint("float_eq_reif", listOf(argB, argC, FznExpr.Ident("__fminmax_b_$suffix")), emptyList())
    emitFloatBinaryCmp(eqA, op = LinearOp.EQ, strict = false, reified = true)
    emitFloatBinaryCmp(eqB, op = LinearOp.EQ, strict = false, reified = true)
    factors.add(
        Clause(
            intArrayOf(
                Lit.make(auxA, true),
                Lit.make(auxB, true),
            ),
        ),
    )
}

/** `float_div(a, b, c)` constrains `c = a / b`; model it as `float_times(b, c, a)` (`a = b·c`). The
 *  arity is checked before the args are reordered so a truncated call fails with a located error. */
internal fun FlatZincCompiler.emitFloatDiv(c: FznConstraint) {
    expectArity(c, 3)
    val denominator = resolveFloatVarOrConst(c.args[1])
    if ((denominator as? FloatRef.Const)?.value == 0.0) {
        failHere("float_div: division by zero")
    }
    if (denominator is FloatRef.Var) {
        emitFloatBinaryCmp(
            FznConstraint("float_ne", listOf(c.args[1], FznExpr.FloatLit(0.0)), emptyList()),
            LinearOp.NE,
            strict = false,
            reified = false,
        )
    }
    emitFloatTimes(
        FznConstraint(
            name = "float_times",
            args = listOf(c.args[1], c.args[2], c.args[0]),
            annotations = c.annotations,
        ),
    )
}

/** Retain `float_times` under its component's exact or rounded grid semantics. */
internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    expectArity(c, 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    emitIntegerImageProduct(c, cRef)
    if (aRef is FloatRef.Const && bRef is FloatRef.Const) {
        if (cRef !is FloatRef.Var || cRef.bk.lpOnly) {
            emitFiniteFloatProduct(aRef, bRef, cRef)
            return
        }
        emitFloatBinaryCmp(
            FznConstraint("float_eq", listOf(c.args[2], FznExpr.FloatLit(aRef.value * bRef.value)), emptyList()),
            LinearOp.EQ,
            strict = false,
            reified = false,
        )
        return
    }
    if ((aRef is FloatRef.Const) != (bRef is FloatRef.Const)) {
        val k = (aRef as? FloatRef.Const)?.value ?: (bRef as FloatRef.Const).value
        val xArg = if (aRef is FloatRef.Const) c.args[1] else c.args[0]
        emitFloatLinear(
            FznConstraint(
                "float_lin_eq",
                listOf(
                    FznExpr.ArrayLit(
                        if (cRef is FloatRef.Const) {
                            listOf(FznExpr.FloatLit(k))
                        } else {
                            listOf(FznExpr.FloatLit(k), FznExpr.FloatLit(-1.0))
                        },
                    ),
                    FznExpr.ArrayLit(if (cRef is FloatRef.Const) listOf(xArg) else listOf(xArg, c.args[2])),
                    FznExpr.FloatLit((cRef as? FloatRef.Const)?.value ?: 0.0),
                ),
                emptyList(),
            ),
            reified = false,
        )
        return
    }
    val aImage = (aRef as? FloatRef.Var)?.takeIf { it.bk.lpOnly }?.let { integerFloatImages[it.bk.varId] }
    val bImage = (bRef as? FloatRef.Var)?.takeIf { it.bk.lpOnly }?.let { integerFloatImages[it.bk.varId] }
    val image = aImage ?: bImage
    val realRef = if (aImage != null) bRef else aRef
    if (cRef is FloatRef.Var && cRef.bk.lpOnly && image != null && realRef is FloatRef.Var && realRef.bk.lpOnly) {
        emitIntegerFloatProduct(image, realRef, cRef)
        return
    }
    if ((aRef as? FloatRef.Var)?.bk?.lpOnly == true || (bRef as? FloatRef.Var)?.bk?.lpOnly == true) {
        emitFiniteFloatProduct(aRef, bRef, cRef)
        return
    }
    factors.add(GridProduct(gridArithmeticCoordinate(aRef, "__grid_product"),
        gridArithmeticCoordinate(bRef, "__grid_product"), gridArithmeticCoordinate(cRef, "__grid_product")))
}

/** Retain `float_abs(x, y)` under its component's exact or bucketed semantics. */
internal fun FlatZincCompiler.emitFloatAbs(c: FznConstraint) {
    expectArity(c, 2)
    val xRef = resolveFloatVarOrConst(c.args[0])
    val yRef = resolveFloatVarOrConst(c.args[1])
    if (xRef is FloatRef.Const) {
        // |constant| is itself a constant: constrain the result to equal it, via the linear path.
        emitFloatLinear(
            FznConstraint(
                "float_lin_eq",
                listOf(
                    FznExpr.ArrayLit(listOf(FznExpr.FloatLit(1.0))),
                    FznExpr.ArrayLit(listOf(c.args[1])),
                    FznExpr.FloatLit(abs(xRef.value)),
                ),
                emptyList(),
            ),
            reified = false,
        )
        return
    }
    val x = (xRef as FloatRef.Var).bk
    val y = when (yRef) {
        is FloatRef.Var -> yRef.bk
        is FloatRef.Const -> resolveFloatElement(c.args[1], "__float_abs_${floatVars.size}", x.lpOnly)
    }
    if (x.lpOnly) {
        factors.add(com.eignex.klause.factor.arithmetic.RealAbs(x.varId, y.varId))
        return
    }
    val relation = com.eignex.klause.factor.arithmetic.GridAbs(
        x.varId, y.varId, x.lo, x.hi, x.buckets, y.lo, y.hi, y.buckets,
    )
    if (!relation.hasImage) failHere("float_abs: not representable under the current float bucketing")
    factors.add(relation)
}

internal fun FlatZincCompiler.emitArrayFloatElement(c: FznConstraint) = emitFloatSelection(c)

internal fun FlatZincCompiler.evalFloatVarArray(e: FznExpr): List<FloatBucketing> = when (e) {
    is FznExpr.ArrayLit -> {
        val refs = e.elements.map(::resolveFloatVarOrConst)
        val vars = refs.filterIsInstance<FloatRef.Var>()
        val lpOnly = (exactFloats || vars.isNotEmpty()) && vars.all { it.bk.lpOnly }
        e.elements.map { resolveFloatElement(it, "__float_${floatVars.size}", lpOnly) }
    }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars ->
            arr.floatBucketings
                ?: failHere("`${e.name}` is not a float var array")

        else -> failHere("`${e.name}` is not a float var array")
    }

    else -> failHere("expected float var array, got ${e::class.simpleName}")
}

private data class ScaledFloatLinear(val coeffs: LongArray, val vars: IntArray, val bound: Long)

private fun FlatZincCompiler.resolveScaledFloatLinear(
    c: FznConstraint,
    reified: Boolean,
    varRefs: List<FloatBucketing> = evalFloatVarArray(c.args[1]),
): ScaledFloatLinear {
    expectArity(c, if (reified) 4 else 3)
    val coefs = evalFloatConstArray(c.args[0])
    if (coefs.size != varRefs.size) failHere("float linear coefficient and variable arrays have different lengths")
    val bound = evalFloatConst(c.args[2])
    var scaledBound = scaledFloat(bound)
    val scaledCoeffs = LongArray(coefs.size)
    val vars = IntArray(coefs.size)
    for (i in coefs.indices) {
        val bk = varRefs[i]
        val step = if (bk.buckets > 1) (bk.hi - bk.lo) / (bk.buckets - 1) else 0.0
        scaledCoeffs[i] = scaledFloat(coefs[i] * step)
        vars[i] = bk.varId
        scaledBound = subtractScaledFloat(scaledBound, scaledFloat(coefs[i] * bk.lo))
    }
    return ScaledFloatLinear(scaledCoeffs, vars, scaledBound)
}

private fun FlatZincCompiler.scaledFloat(value: Double): Long {
    val scaled = value * floatScale
    if (!scaled.isFinite() || scaled < Long.MIN_VALUE.toDouble() || scaled >= Long.MAX_VALUE.toDouble()) {
        unsupportedHere("float bucketing exceeds 64-bit scaled arithmetic; use --exact or a smaller range or scale")
    }
    return scaled.roundToLong()
}

private fun FlatZincCompiler.subtractScaledFloat(left: Long, right: Long): Long = try {
    subExact(left, right)
} catch (_: CheckedLongOverflowException) {
    unsupportedHere("float bucketing exceeds 64-bit scaled arithmetic; use --exact or a smaller range or scale")
}
