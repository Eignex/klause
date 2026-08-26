package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.IntFunctionLowering
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp

internal fun FlatZincCompiler.emitIntCmp(c: FznConstraint) {
    expectArity(c, 2)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
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
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b), op, strictAdjust))
}

/** Post a linear relation as a hard [Linear], or a [ReifiedLinear] onto [reifyLit] when non-null
 *  ([reifyLit] is a bool literal; its variable channels the relation's truth). The single lowering
 *  point every `*_lin_*` / linear-compare emitter funnels through, hard or `_reif`. */
internal fun FlatZincCompiler.postLinear(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long, reifyLit: Int?) {
    factors.add(
        if (reifyLit != null) {
            ReifiedLinear(Lit.variable(reifyLit), coeffs, vars, op, bound)
        } else {
            Linear(coeffs, vars, op, bound)
        },
    )
}

internal fun FlatZincCompiler.emitIntLinear(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 4 else 3)
    val coeffs = evalIntConstArrayLong(c.args[0])
    val vars = evalIntVarArray(c.args[1])
    val bound = evalIntConst(c.args[2])
    val op = when (c.name.removeSuffix("_reif")) {
        "int_lin_le" -> LinearOp.LE
        "int_lin_eq" -> LinearOp.EQ
        "int_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled int linear ${c.name}")
    }
    postLinear(coeffs, vars, op, bound, reifyLit = if (reified) resolveBoolLit(c.args[3]) else null)
}

internal fun FlatZincCompiler.emitBoolLinear(c: FznConstraint) {
    // Negative coefficients would require bool-to-int channeling.
    expectArity(c, 3)
    val coefs = evalIntConstArrayLong(c.args[0])
    val bools = evalBoolVarArray(c.args[1])
    val bound = evalIntConst(c.args[2])
    if (coefs.any { it < 0 }) failHere("bool_lin_* with negative coefficients not supported")
    val op = when (c.name) {
        "bool_lin_le" -> PbOp.LE
        "bool_lin_eq" -> PbOp.EQ
        else -> failHere("unhandled bool linear ${c.name}")
    }
    factors.add(PseudoBoolean(coefs, bools, op, bound))
}

internal fun FlatZincCompiler.emitIntTimes(c: FznConstraint) {
    expectArity(c, 3)
    factors.add(
        Product(
            a = resolveIntVar(c.args[0]),
            b = resolveIntVar(c.args[1]),
            result = resolveIntVar(c.args[2]),
        ),
    )
}

private fun FlatZincCompiler.emitIntAddSub(c: FznConstraint, bCoeff: Int) {
    expectArity(c, 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    factors.add(Linear(intArrayOf(1, bCoeff, -1), intArrayOf(a, b, r), LinearOp.EQ, 0))
}

internal fun FlatZincCompiler.emitIntPlus(c: FznConstraint) = emitIntAddSub(c, bCoeff = 1)

internal fun FlatZincCompiler.emitIntMinus(c: FznConstraint) = emitIntAddSub(c, bCoeff = -1)

internal fun FlatZincCompiler.emitIntAbs(c: FznConstraint) {
    expectArity(c, 2)
    val a = resolveIntVar(c.args[0])
    val r = resolveIntVar(c.args[1])
    var i = 0
    factors.addAll(IntFunctionLowering.absFactors(a, r) { allocBool("__abs_${a}_${r}_${i++}") })
}

internal fun FlatZincCompiler.emitIntMaxMin(c: FznConstraint, max: Boolean) {
    expectArity(c, 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    var i = 0
    factors.addAll(
        IntFunctionLowering.minMaxFactors(r, intArrayOf(a, b), isMax = max) { allocBool("__mm_${a}_${b}_${r}_${i++}") },
    )
}

/** FlatZinc `int_div` uses truncated-toward-zero semantics. */
internal fun FlatZincCompiler.emitIntDiv(c: FznConstraint) {
    expectArity(c, 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val q = resolveIntVar(c.args[2])
    emitTruncDivMod(a, b, qVar = q, remVar = null)
}

/** `int_mod` shares truncated div/mod lowering with [emitIntDiv]. */
internal fun FlatZincCompiler.emitIntMod(c: FznConstraint) {
    expectArity(c, 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val rem = resolveIntVar(c.args[2])
    emitTruncDivMod(a, b, qVar = null, remVar = rem)
}

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

internal fun FlatZincCompiler.emitArrayIntElement(c: FznConstraint, varArray: Boolean) {
    expectArity(c, 3)
    val idx = resolveIntVar(c.args[0])
    val result = resolveIntVar(c.args[2])
    val arr = if (varArray) {
        evalIntVarArray(c.args[1]).let { a -> LongArray(a.size) { a[it].toLong() } }
    } else {
        evalIntConstArrayLong(c.args[1])
    }
    factors.add(Element(idx = idx, result = result, arr = arr, arrIsVars = varArray, indexOffset = 1))
}

internal fun FlatZincCompiler.emitIntCmpReif(c: FznConstraint) {
    expectArity(c, 3)
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

/** Post a half-reified relation `guard -> C`. [ReifiedLinear] is biconditional-only, so a fresh
 *  bool `cond` mirrors `C` (`cond <-> C`) and a clause `(!guard | cond)` forces `guard -> cond -> C`.
 *  When `guard` is false, `cond` floats freely and leaves `C` unconstrained. */
private fun FlatZincCompiler.postHalfReified(
    coeffs: IntArray,
    vars: IntArray,
    op: LinearOp,
    bound: Int,
    guardLit: Int,
) {
    val cond = allocBool("__imp_$numBoolVars")
    factors.add(ReifiedLinear(cond, coeffs, vars, op, bound))
    factors.add(Clause(intArrayOf(Lit.negate(guardLit), Lit.make(cond, true))))
}

internal fun FlatZincCompiler.emitIntCmpImp(c: FznConstraint) {
    expectArity(c, 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val guard = resolveBoolLit(c.args[2])
    val (op, bound) = when (c.name) {
        "int_eq_imp" -> LinearOp.EQ to 0
        "int_ne_imp" -> LinearOp.NE to 0
        "int_le_imp" -> LinearOp.LE to 0
        "int_lt_imp" -> LinearOp.LE to -1
        "int_ge_imp" -> LinearOp.GE to 0
        "int_gt_imp" -> LinearOp.GE to 1
        else -> failHere("unhandled half-reified int cmp `${c.name}`")
    }
    postHalfReified(intArrayOf(1, -1), intArrayOf(a, b), op, bound, guard)
}

internal fun FlatZincCompiler.emitIntLinearImp(c: FznConstraint) {
    expectArity(c, 4)
    val coeffs = evalIntConstArray(c.args[0])
    val vars = evalIntVarArray(c.args[1])
    val bound = evalIntConst(c.args[2]).toInt()
    val op = when (c.name) {
        "int_lin_le_imp" -> LinearOp.LE
        "int_lin_eq_imp" -> LinearOp.EQ
        "int_lin_ne_imp" -> LinearOp.NE
        else -> failHere("unhandled half-reified int linear `${c.name}`")
    }
    postHalfReified(coeffs, vars, op, bound, resolveBoolLit(c.args[3]))
}

internal fun FlatZincCompiler.emitArrayMinMax(c: FznConstraint, max: Boolean) {
    expectArity(c, 2)
    val result = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(ArrayMinMax(result = result, xs = xs, max = max))
}
