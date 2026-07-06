package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.IntFunctionLowering
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.table.Element
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit

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

internal data class IntVarRef(val varId: Int, val offset: Int)
internal fun FlatZincCompiler.resolveIntVarOrConst(e: FznExpr): IntVarRef = when (e) {
    is FznExpr.IntLit -> {
        val v = resolveIntVar(e)
        IntVarRef(v, 0)
    }

    else -> IntVarRef(resolveIntVar(e), 0)
}

/** Post a linear relation as a hard [Linear], or a [ReifiedLinear] onto [reifyLit] when non-null
 *  ([reifyLit] is a bool literal; its variable channels the relation's truth). The single lowering
 *  point every `*_lin_*` / linear-compare emitter funnels through, hard or `_reif`. */
internal fun FlatZincCompiler.postLinear(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int, reifyLit: Int?) {
    factors.add(
        if (reifyLit != null) {
            ReifiedLinear(Lit.variable(reifyLit), coeffs, vars, op, bound)
        } else {
            Linear(coeffs, vars, op, bound)
        },
    )
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
    postLinear(coeffs, vars, op, bound, reifyLit = if (reified) resolveBoolLit(c.args[3]) else null)
}

internal fun FlatZincCompiler.emitBoolLinear(c: FznConstraint) {
    // Negative coefficients would require bool-to-int channeling.
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
    require(c.args.size == 2)
    val a = resolveIntVar(c.args[0])
    val r = resolveIntVar(c.args[1])
    var i = 0
    factors.addAll(IntFunctionLowering.absFactors(a, r) { allocBool("__abs_${a}_${r}_${i++}") })
}

internal fun FlatZincCompiler.emitIntMaxMin(c: FznConstraint, max: Boolean) {
    require(c.args.size == 3)
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
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val q = resolveIntVar(c.args[2])
    emitTruncDivMod(a, b, qVar = q, remVar = null)
}

/** `int_mod` shares truncated div/mod lowering with [emitIntDiv]. */
internal fun FlatZincCompiler.emitIntMod(c: FznConstraint) {
    require(c.args.size == 3)
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
    require(c.args.size == 3)
    val idx = resolveIntVar(c.args[0])
    val result = resolveIntVar(c.args[2])
    val arr = if (varArray) evalIntVarArray(c.args[1]) else evalIntConstArray(c.args[1])
    factors.add(Element(idx = idx, result = result, arr = arr, arrIsVars = varArray, indexOffset = 1))
}

/** Gecode's element with an explicit index offset: `gecode_int_element(idx, idxoffset, x, c)` means
 *  `c = x[idx − idxoffset]` over the 0-based array `x` (verified against a gecode flatten of `a[i]`:
 *  `a[2]` with `a=[10,20,30]` emits `gecode_int_element(2, 1, [10,20,30], c)` ⇒ c = x[1] = 20). Maps
 *  to [Element] with that offset. `x` may hold variables or constant literals. */
internal fun FlatZincCompiler.emitGecodeIntElement(c: FznConstraint) {
    require(c.args.size == 4)
    val idx = resolveIntVar(c.args[0])
    val idxOffset = evalIntConst(c.args[1]).toInt()
    val arr = evalIntVarArray(c.args[2])
    val result = resolveIntVar(c.args[3])
    factors.add(Element(idx = idx, result = result, arr = arr, arrIsVars = true, indexOffset = idxOffset))
}

internal fun FlatZincCompiler.emitIntCmpReif(c: FznConstraint) {
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

internal fun FlatZincCompiler.emitArrayMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 2)
    val result = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(ArrayMinMax(result = result, xs = xs, max = max))
}
