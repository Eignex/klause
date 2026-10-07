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
    if (exactFloats) {
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

    is FznExpr.IntLit -> FloatRef.Const(e.value.toDouble())

    is FznExpr.Ident -> floatVars[e.name]?.let { FloatRef.Var(it) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Float)?.let { FloatRef.Const(it.value) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Int)?.let { FloatRef.Const(it.value.toDouble()) }
        ?: failHere("`${e.name}` is not a float var or float param")

    is FznExpr.ArrayAccess -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> {
            val bucketings = arr.floatBucketings ?: failHere("`${e.name}` is not a float var array")
            FloatRef.Var(bucketings[arrayOffset(bucketings.size, e.index, e.name)])
        }

        is FlatZincArray.FloatParam -> FloatRef.Const(arr.values[arrayOffset(arr.length, e.index, e.name)])

        is FlatZincArray.IntParam -> FloatRef.Const(arr.values[arrayOffset(arr.length, e.index, e.name)].toDouble())

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
    if (exactFloats) {
        expectArity(c, if (reified) 4 else 3)
        val coefficients = evalFloatConstArray(c.args[0])
        val variables = evalFloatVarArray(c.args[1])
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
    val scaled = resolveScaledFloatLinear(c, reified)
    val strictBound = subtractScaledFloat(scaled.bound, 1L)
    postLinear(
        scaled.coeffs,
        scaled.vars,
        LinearOp.LE,
        strictBound,
        if (reified) resolveBoolLit(c.args[3]) else null,
    )
}

/** Lower `float_min`/`float_max` with inequalities plus equality disjunction. */
internal fun FlatZincCompiler.emitFloatMinMax(c: FznConstraint, max: Boolean) {
    expectArity(c, 3)
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
    if (exactFloats && (resolveFloatVarOrConst(c.args[1]) as? FloatRef.Const)?.value == 0.0) {
        failHere("float_div: division by zero")
    }
    emitFloatTimes(
        FznConstraint(
            name = "float_times",
            args = listOf(c.args[1], c.args[2], c.args[0]),
            annotations = c.annotations,
        ),
    )
}

/** Lower `float_times` to a bucket-index table. */
internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    expectArity(c, 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    if (aRef is FloatRef.Const && bRef is FloatRef.Const) {
        emitFloatBinaryCmp(
            FznConstraint("float_eq", listOf(c.args[2], FznExpr.FloatLit(aRef.value * bRef.value)), emptyList()),
            LinearOp.EQ,
            strict = false,
            reified = false,
        )
        return
    }
    // A constant operand makes the product linear (`c = k·x`), so lower it as a float linear equality
    // rather than a var·var product table. Handles the common `x·k` / `k·x` with a variable result; the
    // genuinely non-linear var·var case keeps the table.
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
    // int·real product: one operand is an `int2float` image of an integer variable and the other operand
    // and the result are LP-only reals. Lower as an exact [RealProduct] `result = n·y` — at a search leaf
    // `n` is fixed, so the product is the exact linear equality the residual LP decides.
    val aInt = (c.args[0] as? FznExpr.Ident)?.name?.let { int2floatSource[it] }
    val bInt = (c.args[1] as? FznExpr.Ident)?.name?.let { int2floatSource[it] }
    val intExpr = aInt ?: bInt // exactly one is non-null in the branch below (an XOR guard)
    val realRef = if (aInt != null) bRef else aRef
    if (cRef is FloatRef.Var && cRef.bk.lpOnly && (aInt != null) != (bInt != null) &&
        intExpr != null && realRef is FloatRef.Var && realRef.bk.lpOnly
    ) {
        val y = realRef.bk
        factors.add(RealProduct(resolveIntVar(intExpr), y.varId, cRef.bk.varId, y.lo, y.hi))
        return
    }
    if (aRef !is FloatRef.Var || bRef !is FloatRef.Var || cRef !is FloatRef.Var) {
        failHere("float_times with constant operand not yet handled (only var·var=var)")
    }
    val a = aRef.bk
    val b = bRef.bk
    val cBk = cRef.bk
    val stepA = if (a.buckets > 1) (a.hi - a.lo) / (a.buckets - 1) else 0.0
    val stepB = if (b.buckets > 1) (b.hi - b.lo) / (b.buckets - 1) else 0.0
    val stepC = if (cBk.buckets > 1) (cBk.hi - cBk.lo) / (cBk.buckets - 1) else 0.0
    val rows = LongArrayList(a.buckets * b.buckets * 3)
    val tolerance = 0.5 // round to nearest bucket
    for (ia in 0 until a.buckets) {
        val va = a.lo + ia * stepA
        for (ib in 0 until b.buckets) {
            val vb = b.lo + ib * stepB
            val vc = va * vb
            if (vc < cBk.lo - stepC * tolerance || vc > cBk.hi + stepC * tolerance) continue
            val ic = if (stepC == 0.0) {
                0
            } else {
                ((vc - cBk.lo) / stepC).let {
                    val rounded = round(it).toInt()
                    if (abs(it - rounded) > tolerance) return@let -1
                    rounded
                }
            }
            if (ic < 0 || ic >= cBk.buckets) continue
            rows.add(ia.toLong())
            rows.add(ib.toLong())
            rows.add(ic.toLong())
        }
    }
    if (rows.isEmpty()) {
        // No bucket triple realises the product within tolerance. This is a resolution limit of the
        // bucketing, not proven infeasibility, so reject rather than report a spurious UNSAT.
        failHere("float_times: product not representable under the current float bucketing")
    }
    factors.add(
        Table(
            intArrayOf(a.varId, b.varId, cBk.varId),
            rows.toLongArray(),
        ),
    )
}

/** Lower `float_abs(x, y)` (`y = |x|`) to a bucket-index table, mirroring [emitFloatTimes]. */
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
        is FloatRef.Const -> resolveFloatElement(c.args[1], "__float_abs_${floatVars.size}", exactFloats)
    }
    if (exactFloats) {
        val variables = intArrayOf(x.varId, y.varId)
        postExactFloatLinear(doubleArrayOf(1.0, -1.0), variables, LinearOp.LE, 0.0)
        postExactFloatLinear(doubleArrayOf(-1.0, -1.0), variables, LinearOp.LE, 0.0)
        factors.add(
            Clause(
                intArrayOf(
                    reifyRealLinear(doubleArrayOf(1.0, -1.0), variables, LinearOp.EQ, 0.0),
                    reifyRealLinear(doubleArrayOf(-1.0, -1.0), variables, LinearOp.EQ, 0.0),
                ),
            ),
        )
        return
    }
    val stepX = if (x.buckets > 1) (x.hi - x.lo) / (x.buckets - 1) else 0.0
    val stepY = if (y.buckets > 1) (y.hi - y.lo) / (y.buckets - 1) else 0.0
    val rows = LongArrayList(x.buckets * 2)
    val tolerance = 0.5
    for (ix in 0 until x.buckets) {
        val vy = abs(x.lo + ix * stepX)
        if (vy < y.lo - stepY * tolerance || vy > y.hi + stepY * tolerance) continue
        val iy = if (stepY == 0.0) {
            0
        } else {
            ((vy - y.lo) / stepY).let {
                val rounded = round(it).toInt()
                if (abs(it - rounded) > tolerance) return@let -1
                rounded
            }
        }
        if (iy < 0 || iy >= y.buckets) continue
        rows.add(ix.toLong())
        rows.add(iy.toLong())
    }
    if (rows.isEmpty()) failHere("float_abs: not representable under the current float bucketing")
    factors.add(Table(intArrayOf(x.varId, y.varId), rows.toLongArray()))
}

/** Lower `array_float_element(idx, arr, x)` (`x = arr[idx]`, 1-based, `arr` a float-constant array)
 *  to a table pairing each valid index value with the bucket of its constant. An index value whose
 *  constant is unrepresentable in the result's bucketing rejects, so a dropped row never silently
 *  forbids a feasible index. */
internal fun FlatZincCompiler.emitArrayFloatElement(c: FznConstraint) {
    expectArity(c, 3)
    val idx = resolveIntVar(c.args[0])
    val arr = evalFloatConstArray(c.args[1])
    val xRef = resolveFloatVarOrConst(c.args[2])
    val x = when (xRef) {
        is FloatRef.Var -> xRef.bk
        is FloatRef.Const -> resolveFloatElement(c.args[2], "__float_element_${floatVars.size}", exactFloats)
    }
    if (exactFloats) {
        factors.add(Linear(longArrayOf(1L), intArrayOf(idx), LinearOp.GE, 1L))
        factors.add(Linear(longArrayOf(1L), intArrayOf(idx), LinearOp.LE, arr.size.toLong()))
        for (i in arr.indices) {
            val selected = reifyLinear(longArrayOf(1L), intArrayOf(idx), LinearOp.EQ, (i + 1).toLong())
            val value = reifyRealLinear(doubleArrayOf(1.0), intArrayOf(x.varId), LinearOp.EQ, arr[i])
            factors.add(Clause(intArrayOf(Lit.negate(selected), value)))
        }
        return
    }
    val stepX = if (x.buckets > 1) (x.hi - x.lo) / (x.buckets - 1) else 0.0
    val dom = intDomains[idx]
    val tolerance = 0.5
    val rows = LongArrayList()
    for (vi in dom.min.toInt()..dom.max.toInt()) {
        val ai = vi - 1 // FlatZinc arrays are 1-based.
        if (ai < 0 || ai >= arr.size) continue // Out-of-range index value: the table forbids it.
        val cv = arr[ai]
        if (cv < x.lo - stepX * tolerance || cv > x.hi + stepX * tolerance) {
            failHere("array_float_element: value $cv not representable under the current float bucketing")
        }
        val ix = if (stepX == 0.0) {
            0
        } else {
            ((cv - x.lo) / stepX).let {
                val rounded = round(it).toInt()
                if (abs(it - rounded) > tolerance) {
                    failHere("array_float_element: value $cv not representable under the current float bucketing")
                }
                rounded
            }
        }
        if (ix < 0 || ix >= x.buckets) {
            failHere("array_float_element: value $cv not representable under the current float bucketing")
        }
        rows.add(vi.toLong())
        rows.add(ix.toLong())
    }
    if (rows.isEmpty()) failHere("array_float_element: no valid index value in the domain")
    factors.add(Table(intArrayOf(idx, x.varId), rows.toLongArray()))
}

internal fun FlatZincCompiler.evalFloatVarArray(e: FznExpr): List<FloatBucketing> = when (e) {
    is FznExpr.ArrayLit -> {
        val refs = e.elements.map(::resolveFloatVarOrConst)
        val lpOnly = exactFloats && refs.filterIsInstance<FloatRef.Var>().all { it.bk.lpOnly }
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
