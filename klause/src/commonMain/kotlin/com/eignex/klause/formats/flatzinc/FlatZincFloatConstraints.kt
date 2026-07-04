package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.*
import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.table.*
import com.eignex.klause.solver.Lit
import kotlin.math.*

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    val scaled = resolveScaledFloatLinear(c, reified)
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_le" -> LinearOp.LE
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled float linear ${c.name}")
    }
    postLinear(scaled.coeffs, scaled.vars, op, scaled.bound.toInt(), if (reified) resolveBoolLit(c.args[3]) else null)
}

/** Lower `int2float` as a scaled linear equality on bucket indices. */
internal fun FlatZincCompiler.emitInt2Float(c: FznConstraint) {
    require(c.args.size == 2)
    val xInt = resolveIntVar(c.args[0])
    val yName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("int2float: second arg must be a float var identifier")
    val yBk = floatVars[yName] ?: failHere("`$yName` is not a float var")
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
    require(c.args.size == if (reified) 3 else 2)
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
                factors.add(Clause(IntArray(0)))
            }
            return
        }

        else -> Unit
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
    var scaledBound = (-sign * constPart * floatScale).roundToLong() -
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
    postLinear(coeffs, vars, op, finalBound.toInt(), if (reified) resolveBoolLit(c.args[2]) else null)
}

/** Strict float linear compare lowered to `<= bound - 1` in scaled space. */
internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
    val scaled = resolveScaledFloatLinear(c, reified)
    val strictBound = scaled.bound - 1
    postLinear(
        scaled.coeffs,
        scaled.vars,
        LinearOp.LE,
        strictBound.toInt(),
        if (reified) resolveBoolLit(c.args[3]) else null,
    )
}

/** Lower `float_min`/`float_max` with inequalities plus equality disjunction. */
internal fun FlatZincCompiler.emitFloatMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 3)
    val argA = c.args[0]
    val argB = c.args[1]
    val argC = c.args[2]
    fun emitIneq(left: FznExpr, right: FznExpr) {
        val fc = FznConstraint(if (max) "float_le" else "float_le", listOf(left, right), emptyList())
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

/** Lower `float_times` to a bucket-index table. */
internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    require(c.args.size == 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    if (aRef !is FloatRef.Var || bRef !is FloatRef.Var || cRef !is FloatRef.Var) {
        failHere("float_times with constant operand not yet handled (only var·var=var)")
    }
    val a = aRef.bk
    val b = bRef.bk
    val cBk = cRef.bk
    val stepA = if (a.buckets > 1) (a.hi - a.lo) / (a.buckets - 1) else 0.0
    val stepB = if (b.buckets > 1) (b.hi - b.lo) / (b.buckets - 1) else 0.0
    val stepC = if (cBk.buckets > 1) (cBk.hi - cBk.lo) / (cBk.buckets - 1) else 0.0
    val rows = ArrayList<Int>(a.buckets * b.buckets * 3)
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
            rows.add(ia)
            rows.add(ib)
            rows.add(ic)
        }
    }
    if (rows.isEmpty()) {
        factors.add(Clause(IntArray(0)))
        return
    }
    factors.add(
        Table(
            intArrayOf(a.varId, b.varId, cBk.varId),
            rows.toIntArray(),
        ),
    )
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
    require(c.args.size == if (reified) 4 else 3)
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
