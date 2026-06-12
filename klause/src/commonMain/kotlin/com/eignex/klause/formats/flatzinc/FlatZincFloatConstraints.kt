package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 4 else 3)
    val coefs = evalFloatConstArray(c.args[0])
    val varRefs = evalFloatVarArray(c.args[1])
    val bound = evalFloatConst(c.args[2])
    // Each float var x_i ∈ [lo_i, hi_i] discretized to N buckets with step_i = (hi-lo)/(N-1).
    // value(i_idx) = lo_i + i_idx * step_i.
    // Σ c_i · value(idx_i) op bound
    // ⇒ Σ c_i · step_i · idx_i op bound - Σ c_i · lo_i
    // Scale all coefficients and bound by `floatScale` and round to integers.
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
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_le" -> LinearOp.LE
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled float linear ${c.name}")
    }
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(ReifiedLinear(Lit.variable(aux), scaledCoeffs, vars, op, scaledBound.toInt()))
    } else {
        factors.add(Linear(scaledCoeffs, vars, op, scaledBound.toInt()))
    }
}

/**
 * `int2float(x_int, y_float)` — coerce x's int value into y's float value. y is
 * backed by a bucket-index int var with `value(idx) = lo + idx * step`. The
 * constraint is `x = lo + idx_y * step`, which rearranges (after scaling by
 * `floatScale`) to a single linear equality over (x, idx_y). Identity buckets
 * (step=1.0, lo integer) are the common case from `var int → var float` lifts.
 */
internal fun FlatZincCompiler.emitInt2Float(c: FznConstraint) {
    require(c.args.size == 2)
    val xInt = resolveIntVar(c.args[0])
    val yName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("int2float: second arg must be a float var identifier")
    val yBk = floatVars[yName] ?: failHere("`$yName` is not a float var")
    val step = if (yBk.buckets > 1) (yBk.hi - yBk.lo) / (yBk.buckets - 1) else 0.0
    // floatScale·x − floatScale·step·idx_y = floatScale·lo.
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

/**
 * Look up a float var by name (rejecting constants and non-float identifiers). Used by
 * the binary float-cmp / min-max / times paths where every argument is expected to be a
 * declared `var float` rather than a parameter or array element.
 */
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

/**
 * `float_eq` / `float_le` / `float_lt` / `float_ne` (+ their `_reif` variants). Rewrites
 * `a op b` as the float-linear `1·a − 1·b op 0`, with [strict] adjusting LE by `-1` scaled
 * unit to encode strict-less. Constants on either side fold into the bound. Delegates to
 * [emitFloatLinear] by synthesising the equivalent `float_lin_*` constraint so the same
 * bucket-scaling logic runs in one place.
 */
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
    // Encode `1·a − 1·b op 0` as scaled coefficients/bound directly (the emitFloatLinear scaling),
    // without round-tripping through FznExpr.
    val step = if (varSide.buckets > 1) (varSide.hi - varSide.lo) / (varSide.buckets - 1) else 0.0
    val coefVar = (sign * step * floatScale).roundToLong()
    var scaledBound = (-sign * constPart * floatScale).roundToLong() -
        (sign * varSide.lo * floatScale).roundToLong()
    // Two-variable case folds the second var symmetrically.
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
    val finalOp = op
    val finalBound = if (op == LinearOp.LE && strict) scaledBound - 1 else scaledBound
    if (reified) {
        val r = resolveBoolLit(c.args[2])
        factors.add(
            ReifiedLinear(
                Lit.variable(r),
                coeffs,
                vars,
                finalOp,
                finalBound.toInt(),
            ),
        )
    } else {
        factors.add(Linear(coeffs, vars, finalOp, finalBound.toInt()))
    }
}

/**
 * `float_lin_lt(coeffs, vars, bound)` — strict `Σ c·v < bound`. Reduce to
 * `float_lin_le` with `bound − 1` scaled unit so the strict semantics is preserved at
 * bucket granularity (sound since `floatScale` is the smallest representable diff).
 */
internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
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
    // Strict: subtract one scaled unit.
    scaledBound -= 1
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(
            ReifiedLinear(
                Lit.variable(aux),
                scaledCoeffs,
                vars,
                LinearOp.LE,
                scaledBound.toInt(),
            ),
        )
    } else {
        factors.add(Linear(scaledCoeffs, vars, LinearOp.LE, scaledBound.toInt()))
    }
}

/**
 * `float_min(a, b, c)` / `float_max(a, b, c)` — c = min/max(a, b). Lowered as the pair
 * of bucket-level inequalities `c ≤ a, c ≤ b` (min) or `c ≥ a, c ≥ b` (max), plus a
 * reified disjunction `c = a ∨ c = b` so c is forced to the bound by one of the inputs.
 */
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
        // c ≥ a  ⇔  a ≤ c
        emitIneq(argA, argC)
        emitIneq(argB, argC)
    } else {
        // c ≤ a, c ≤ b
        emitIneq(argC, argA)
        emitIneq(argC, argB)
    }
    // Disjunction c = a ∨ c = b via two reified equalities and a clause. Unique
    // suffix ties the aux bools to this constraint's position in the factor list,
    // so multiple float_min/max in one model don't collide.
    val suffix = factors.size.toString()
    val auxA = allocBool("__fminmax_a_$suffix")
    val auxB = allocBool("__fminmax_b_$suffix")
    val eqA = FznConstraint("float_eq_reif", listOf(argA, argC, FznExpr.Ident("__fminmax_a_$suffix")), emptyList())
    val eqB = FznConstraint("float_eq_reif", listOf(argB, argC, FznExpr.Ident("__fminmax_b_$suffix")), emptyList())
    // Note: emitFloatBinaryCmp resolves the bool lit via resolveBoolLit which looks up the
    // identifier in [boolVars]; allocBool registers there.
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

/**
 * `float_times(a, b, c)` — c = a · b. Non-linear, lowered by enumerating the Cartesian
 * product of (a, b) bucket indices and emitting an N_a · N_b row [Table] over
 * (idx_a, idx_b, idx_c). The c column is computed by `value(a)·value(b)` rounded to c's
 * closest bucket index; rows whose rounded c falls outside c's domain are dropped (the
 * constraint forbids them). Sound but quadratic in bucket counts — use with care on
 * coarsely bucketed floats.
 */
internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    require(c.args.size == 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    if (aRef !is FloatRef.Var || bRef !is FloatRef.Var || cRef !is FloatRef.Var) {
        // At least one side constant — degenerate to a linear constraint.
        // (No corpus case exercises this; defer if encountered.)
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
        // No feasible row — infeasible.
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
