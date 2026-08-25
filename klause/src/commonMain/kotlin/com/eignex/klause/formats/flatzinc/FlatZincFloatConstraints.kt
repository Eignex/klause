package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.*
import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.table.*
import com.eignex.klause.formats.FloatBucketing
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import kotlin.math.*

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    val varRefsAll = evalFloatVarArray(c.args[1])
    if (!reified && varRefsAll.isNotEmpty() && varRefsAll.all { it.lpOnly }) {
        // LP-only floats: emit the raw double coefficients over the real columns, no bucket scaling. Only
        // LE/EQ float_lin colours a float LP-only (see classifyLpOnlyFloats), so reified/ne never reach here.
        val coefs = evalFloatConstArray(c.args[0])
        val varRefs = varRefsAll
        val op = if (c.name == "float_lin_eq") LinearOp.EQ else LinearOp.LE
        factors.add(
            Linear(
                EmptyIntArray,
                EmptyDoubleArray,
                IntArray(coefs.size) { varRefs[it].varId },
                coefs,
                op,
                evalFloatConst(c.args[2]),
            ),
        )
        return
    }
    val scaled = resolveScaledFloatLinear(c, reified)
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_le" -> LinearOp.LE
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled float linear ${c.name}")
    }
    postLinear(scaled.coeffs.toLongs(), scaled.vars, op, scaled.bound, if (reified) resolveBoolLit(c.args[3]) else null)
}

private fun IntArray.toLongs(): LongArray = LongArray(size) { this[it].toLong() }

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
    val cIdxY = (-step * floatScale).roundToLong()
    val bound = (yBk.lo * floatScale).roundToLong()
    factors.add(
        Linear(
            intArrayOf(cX.toInt(), cIdxY.toInt()),
            intArrayOf(xInt, yBk.varId),
            LinearOp.EQ,
            bound.toInt(),
        ),
    )
}

private fun FlatZincCompiler.resolveFloatVarOrConst(e: FznExpr): FloatRef = when (e) {
    is FznExpr.FloatLit -> FloatRef.Const(e.value)

    is FznExpr.IntLit -> FloatRef.Const(e.value.toDouble())

    is FznExpr.Ident -> floatVars[e.name]?.let { FloatRef.Var(it) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Float)?.let { FloatRef.Const(it.value) }
        ?: failHere("`${e.name}` is not a float var or float param")

    else -> failHere("expected float var or float constant, got ${e::class.simpleName}")
}

private sealed interface FloatRef {
    data class Var(val bk: FloatBucketing) : FloatRef
    data class Const(val value: Double) : FloatRef
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
    if (varLpOnly && !strict && !reified) {
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
        factors.add(Linear(EmptyIntArray, EmptyDoubleArray, rv.toIntArray(), rc.toDoubleArray(), op, bound))
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
    val coefVar = (sign * step * floatScale).roundToLong()
    // value(var) = lo + step·bucket, so `a OP b` with one constant is coefVar·bucket OP
    // sign·(const − lo)·scale (the var-var branch below overrides this bound).
    var scaledBound = (sign * constPart * floatScale).roundToLong() -
        (sign * varSide.lo * floatScale).roundToLong()
    val coeffs: IntArray
    val vars: IntArray
    if (a is FloatRef.Var && b is FloatRef.Var) {
        val stepB = if (b.bk.buckets > 1) (b.bk.hi - b.bk.lo) / (b.bk.buckets - 1) else 0.0
        coeffs = intArrayOf(coefVar.toInt(), (-1.0 * stepB * floatScale).roundToLong().toInt())
        vars = intArrayOf(varSide.varId, b.bk.varId)
        scaledBound = (b.bk.lo * floatScale).roundToLong() -
            (varSide.lo * floatScale).roundToLong()
    } else {
        coeffs = intArrayOf(coefVar.toInt())
        vars = intArrayOf(varSide.varId)
    }
    val finalBound = if (op == LinearOp.LE && strict) scaledBound - 1 else scaledBound
    postLinear(coeffs.toLongs(), vars, op, finalBound, if (reified) resolveBoolLit(c.args[2]) else null)
}

/** Strict float linear compare lowered to `<= bound - 1` in scaled space. */
internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
    val scaled = resolveScaledFloatLinear(c, reified)
    val strictBound = scaled.bound - 1
    postLinear(
        scaled.coeffs.toLongs(),
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
    // A constant operand makes the product linear (`c = k·x`), so lower it as a float linear equality
    // rather than a var·var product table. Handles the common `x·k` / `k·x` with a variable result; the
    // genuinely non-linear var·var case keeps the table.
    if (cRef is FloatRef.Var && (aRef is FloatRef.Const) != (bRef is FloatRef.Const)) {
        val k = (aRef as? FloatRef.Const)?.value ?: (bRef as FloatRef.Const).value
        val xArg = if (aRef is FloatRef.Const) c.args[1] else c.args[0]
        emitFloatLinear(
            FznConstraint(
                "float_lin_eq",
                listOf(
                    FznExpr.ArrayLit(listOf(FznExpr.FloatLit(k), FznExpr.FloatLit(-1.0))),
                    FznExpr.ArrayLit(listOf(xArg, c.args[2])),
                    FznExpr.FloatLit(0.0),
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
    if (yRef !is FloatRef.Var) failHere("float_abs: result must be a float var")
    val x = (xRef as FloatRef.Var).bk
    val y = yRef.bk
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
    if (xRef !is FloatRef.Var) failHere("array_float_element: result must be a float var")
    val x = xRef.bk
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
    is FznExpr.ArrayLit -> e.elements.map {
        val name = (it as? FznExpr.Ident)?.name
            ?: failHere("float var array: expected identifier element")
        floatVars[name] ?: failHere("`$name` is not a float var")
    }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars ->
            arr.floatBucketings
                ?: failHere("`${e.name}` is not a float var array")

        else -> failHere("`${e.name}` is not a float var array")
    }

    else -> failHere("expected float var array, got ${e::class.simpleName}")
}

private data class ScaledFloatLinear(val coeffs: IntArray, val vars: IntArray, val bound: Long)

private fun FlatZincCompiler.resolveScaledFloatLinear(c: FznConstraint, reified: Boolean): ScaledFloatLinear {
    expectArity(c, if (reified) 4 else 3)
    val coefs = evalFloatConstArray(c.args[0])
    val varRefs = evalFloatVarArray(c.args[1])
    val bound = evalFloatConst(c.args[2])
    var scaledBound = (bound * floatScale).roundToLong()
    val scaledCoeffs = IntArray(coefs.size)
    val vars = IntArray(coefs.size)
    for (i in coefs.indices) {
        val bk = varRefs[i]
        val step = if (bk.buckets > 1) (bk.hi - bk.lo) / (bk.buckets - 1) else 0.0
        scaledCoeffs[i] = (coefs[i] * step * floatScale).roundToLong().toInt()
        vars[i] = bk.varId
        scaledBound -= (coefs[i] * bk.lo * floatScale).roundToLong()
    }
    return ScaledFloatLinear(scaledCoeffs, vars, scaledBound)
}
