package com.eignex.klause.formats.flatzinc

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.IntFunctionLowering
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear

internal fun FlatZincCompiler.emitIntCmp(c: FznConstraint) {
    require(c.args.size == 2)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVarOrConst(c.args[1])
    val op = when (c.name) {
        "int_le", "int_lt" -> LinearOp.LE
        "int_eq" -> LinearOp.EQ
        "int_ne" -> LinearOp.NE
        "int_ge", "int_gt" -> LinearOp.GE
        else -> failHere("unhandled int cmp ${c.name}")
    }
    val strictAdjust = when (c.name) {
        "int_lt" -> -1
        "int_gt" -> 1
        else -> 0
    }
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), op, strictAdjust - b.offset))
}

/** An int var, possibly with a constant offset on the right side. */
internal data class IntVarRef(val varId: Int, val offset: Int)
internal fun FlatZincCompiler.resolveIntVarOrConst(e: FznExpr): IntVarRef = when (e) {
    is FznExpr.IntLit -> {
        // Allocate a singleton var holding the constant.
        val v = resolveIntVar(e)
        IntVarRef(v, 0)
    }

    else -> IntVarRef(resolveIntVar(e), 0)
}

internal fun FlatZincCompiler.emitIntLinear(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 4 else 3)
    val coeffs = evalIntConstArray(c.args[0])
    val vars = evalIntVarArray(c.args[1])
    val bound = evalIntConst(c.args[2]).toInt()
    val op = when (c.name.removeSuffix("_reif")) {
        "int_lin_le" -> LinearOp.LE
        "int_lin_eq" -> LinearOp.EQ
        "int_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled int linear ${c.name}")
    }
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(ReifiedLinear(Lit.variable(aux), coeffs, vars, op, bound))
    } else {
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

internal fun FlatZincCompiler.emitBoolLinear(c: FznConstraint) {
    // bool_lin_*(coefs, bools, k): a PseudoBoolean over the bool literals. Negative coefficients
    // would need bool2int channels (a Linear over channelled ints), unsupported — reject them.
    require(c.args.size == 3)
    val coefs = evalIntConstArray(c.args[0])
    val bools = evalBoolVarArray(c.args[1])
    val bound = evalIntConst(c.args[2]).toInt()
    if (coefs.any { it < 0 }) failHere("bool_lin_* with negative coefficients not supported")
    val op = when (c.name) {
        "bool_lin_le" -> PbOp.LE
        "bool_lin_eq" -> PbOp.EQ
        else -> failHere("unhandled bool linear ${c.name}")
    }
    factors.add(PseudoBoolean(coefs, bools, op, bound))
}

internal fun FlatZincCompiler.emitIntTimes(c: FznConstraint) {
    require(c.args.size == 3)
    factors.add(
        Product(
            a = resolveIntVar(c.args[0]),
            b = resolveIntVar(c.args[1]),
            result = resolveIntVar(c.args[2]),
        ),
    )
}

/** `int_plus(a, b, r)` / `int_minus(a, b, r)`: `a ± b = r` as `a + [bCoeff]·b − r = 0`. */
private fun FlatZincCompiler.emitIntAddSub(c: FznConstraint, bCoeff: Int) {
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    factors.add(Linear(intArrayOf(1, bCoeff, -1), intArrayOf(a, b, r), LinearOp.EQ, 0))
}

internal fun FlatZincCompiler.emitIntPlus(c: FznConstraint) = emitIntAddSub(c, bCoeff = 1)

internal fun FlatZincCompiler.emitIntMinus(c: FznConstraint) = emitIntAddSub(c, bCoeff = -1)

internal fun FlatZincCompiler.emitIntAbs(c: FznConstraint) {
    // int_abs(a, r): r = |a|. Shared encoding — see [IntFunctionLowering.absFactors].
    require(c.args.size == 2)
    val a = resolveIntVar(c.args[0])
    val r = resolveIntVar(c.args[1])
    var i = 0
    factors.addAll(IntFunctionLowering.absFactors(a, r) { allocBool("__abs_${a}_${r}_${i++}") })
}

internal fun FlatZincCompiler.emitIntMaxMin(c: FznConstraint, max: Boolean) {
    // int_max(a, b, r) / int_min(a, b, r). Shared encoding — see [IntFunctionLowering.minMaxFactors].
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    var i = 0
    factors.addAll(
        IntFunctionLowering.minMaxFactors(r, intArrayOf(a, b), isMax = max) { allocBool("__mm_${a}_${b}_${r}_${i++}") },
    )
}

/**
 * `int_div(a, b, q)` — truncated-toward-zero `q = a / b`. Encoded as:
 *  - `q · b + rem = a` (via [Product] on `q · b` and a [Linear] sum)
 *  - `|rem| < |b|` (via two [Linear]s gated by aux bool indicating sign of `b`)
 *  - `sign(rem) ∈ {0, sign(a)}` (truncated semantics — via reified linears)
 *
 * Klause-internal `int_div` uses Euclidean div elsewhere; this emitter implements
 * FlatZinc's truncated semantics specifically. `b = 0` is left to propagation (the
 * caller's responsibility — typical models constrain `b != 0` via domains).
 */
internal fun FlatZincCompiler.emitIntDiv(c: FznConstraint) {
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val q = resolveIntVar(c.args[2])
    emitTruncDivMod(a, b, qVar = q, remVar = null)
}

/** `int_mod(a, b, rem)` — same constraint shape as [emitIntDiv], but with `rem` exposed
 *  and `q` allocated as the auxiliary quotient. */
internal fun FlatZincCompiler.emitIntMod(c: FznConstraint) {
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val rem = resolveIntVar(c.args[2])
    emitTruncDivMod(a, b, qVar = null, remVar = rem)
}

/** Bridge the FlatZinc allocator onto the shared [IntFunctionLowering.truncatedDivMod]. */
private fun FlatZincCompiler.emitTruncDivMod(a: Int, b: Int, qVar: Int?, remVar: Int?) {
    var n = 0
    val res = IntFunctionLowering.truncatedDivMod(
        a,
        b,
        intDomains[a],
        intDomains[b],
        quotient = qVar,
        remainder = remVar,
        freshInt = { d -> allocInt("__divmod_${a}_${b}_i${n++}", d.min, d.max) },
        freshBool = { allocBool("__divmod_${a}_${b}_b${n++}") },
    )
    factors.addAll(res.factors)
}

/**
 * `array_int_element(idx, arr, result)` / `array_var_int_element(idx, arr, result)`:
 * `result = arr(idx)` with 1-based indexing.
 */
internal fun FlatZincCompiler.emitArrayIntElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val idx = resolveIntVar(c.args[0])
    val result = resolveIntVar(c.args[2])
    val arr = if (varArray) evalIntVarArray(c.args[1]) else evalIntConstArray(c.args[1])
    factors.add(Element(idx = idx, result = result, arr = arr, arrIsVars = varArray, indexOffset = 1))
}

internal fun FlatZincCompiler.emitIntCmpReif(c: FznConstraint) {
    // int_{eq,ne,le,lt,ge,gt}_reif(a, b, r): r ↔ (a ⟨op⟩ b). All reduce to
    // ReifiedLinear with appropriate coeffs / bound.
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = Lit.variable(resolveBoolLit(c.args[2]))
    val coeffs = intArrayOf(1, -1)
    val vars = intArrayOf(a, b)
    val (op, bound) = when (c.name) {
        "int_eq_reif" -> LinearOp.EQ to 0
        "int_ne_reif" -> LinearOp.NE to 0
        "int_le_reif" -> LinearOp.LE to 0
        "int_lt_reif" -> LinearOp.LE to -1
        "int_ge_reif" -> LinearOp.GE to 0
        "int_gt_reif" -> LinearOp.GE to 1
        else -> failHere("unhandled reified int cmp `${c.name}`")
    }
    factors.add(ReifiedLinear(r, coeffs, vars, op, bound))
}

/** `array_int_maximum(result, xs)` / `array_int_minimum(...)` — single [ArrayMinMax] factor. */
internal fun FlatZincCompiler.emitArrayMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 2)
    val result = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(ArrayMinMax(result = result, xs = xs, max = max))
}
