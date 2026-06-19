package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.solver.factor.global.LexLess
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.factor.linear.ReifiedLinear
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.factor.table.Table

internal fun FlatZincCompiler.emitBoolClause(c: FznConstraint) {
    require(c.args.size == 2)
    val pos = evalBoolVarArray(c.args[0])
    val neg = evalBoolVarArrayNegated(c.args[1])
    factors.add(Clause(pos + neg))
}

internal fun FlatZincCompiler.evalBoolVarArrayNegated(e: FznExpr): IntArray = when (e) {
    is FznExpr.ArrayLit -> IntArray(e.elements.size) { Lit.negate(resolveBoolLit(e.elements[it])) }

    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> IntArray(arr.varIds.size) { Lit.make(arr.varIds[it], false) }
        else -> failHere("`${e.name}` is not a bool var array")
    }

    else -> failHere("expected bool var array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.emitBoolEq(c: FznConstraint, negate: Boolean) {
    require(c.args.size == 2)
    val a = resolveBoolLit(c.args[0])
    val b = if (negate) Lit.negate(resolveBoolLit(c.args[1])) else resolveBoolLit(c.args[1])
    factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    factors.add(Clause(intArrayOf(a, Lit.negate(b))))
}

internal fun FlatZincCompiler.emitBoolXor(c: FznConstraint) {
    require(c.args.size == 3)
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

/** Shared lowering for `array_bool_or` and `array_bool_and`. */
private fun FlatZincCompiler.emitArrayBoolReduction(c: FznConstraint, isOr: Boolean) {
    require(c.args.size == 2)
    val lits = evalBoolVarArray(c.args[0])
    val r = resolveBoolLit(c.args[1])
    val body = if (isOr) lits else IntArray(lits.size) { Lit.negate(lits[it]) }
    factors.add(Clause(body + intArrayOf(if (isOr) Lit.negate(r) else r)))
    for (l in lits) {
        factors.add(Clause(if (isOr) intArrayOf(Lit.negate(l), r) else intArrayOf(Lit.negate(r), l)))
    }
}

internal fun FlatZincCompiler.emitArrayBoolOr(c: FznConstraint) = emitArrayBoolReduction(c, isOr = true)

internal fun FlatZincCompiler.emitArrayBoolAnd(c: FznConstraint) = emitArrayBoolReduction(c, isOr = false)

internal fun FlatZincCompiler.emitBoolAndOr(c: FznConstraint, and: Boolean) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    if (and) {
        factors.add(Clause(intArrayOf(Lit.negate(r), a)))
        factors.add(Clause(intArrayOf(Lit.negate(r), b)))
        factors.add(Clause(intArrayOf(r, Lit.negate(a), Lit.negate(b))))
    } else {
        factors.add(Clause(intArrayOf(r, Lit.negate(a))))
        factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        factors.add(Clause(intArrayOf(Lit.negate(r), a, b)))
    }
}

internal fun FlatZincCompiler.emitBoolXorReif(c: FznConstraint) {
    require(c.args.size == 3)
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

internal fun FlatZincCompiler.emitArrayBoolXor(c: FznConstraint) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    factors.add(Xor(lits, targetParity = 1))
}

internal fun FlatZincCompiler.emitBoolCmp(c: FznConstraint, lt: Boolean, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    if (lt) {
        factors.add(Clause(intArrayOf(Lit.negate(a))))
        factors.add(Clause(intArrayOf(b)))
    } else {
        factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    }
}

internal fun FlatZincCompiler.emitBoolCmpReif(c: FznConstraint, op: BoolCmpOp) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    when (op) {
        BoolCmpOp.EQ -> {
            factors.add(Xor(intArrayOf(a, b, Lit.negate(r)), targetParity = 0))
        }

        BoolCmpOp.LE -> {
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a), b)))
            factors.add(Clause(intArrayOf(r, a)))
            factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        }

        BoolCmpOp.LT -> {
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a))))
            factors.add(Clause(intArrayOf(Lit.negate(r), b)))
            factors.add(Clause(intArrayOf(a, Lit.negate(b), r)))
        }
    }
}

internal fun FlatZincCompiler.emitBool2Int(c: FznConstraint) {
    val b = resolveBoolLit(c.args[0])
    val x = resolveIntVar(c.args[1])
    factors.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.GE, 0))
    factors.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.LE, 1))
    val targetBound = if (Lit.isPositive(b)) 1 else 0
    factors.add(
        ReifiedLinear(
            Lit.variable(b),
            coeffs = intArrayOf(1),
            vars = intArrayOf(x),
            op = LinearOp.EQ,
            bound = targetBound,
        ),
    )
}

/** Shared lowering for bool element constraints. */
internal fun FlatZincCompiler.emitArrayBoolElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val idx = resolveIntVar(c.args[0])
    val resultLit = resolveBoolLit(c.args[2])
    val resultInt = channelBoolsToInts(intArrayOf(resultLit), "belem_res")[0]
    if (varArray) {
        val arrLits = evalBoolVarArray(c.args[1])
        val arr = channelBoolsToInts(arrLits, "belem")
        factors.add(Element(idx = idx, result = resultInt, arr = arr, arrIsVars = true, indexOffset = 1))
    } else {
        val arrConst = evalBoolConstArray(c.args[1])
        val arr = IntArray(arrConst.size) { if (arrConst[it]) 1 else 0 }
        factors.add(Element(idx = idx, result = resultInt, arr = arr, arrIsVars = false, indexOffset = 1))
    }
}

/** Channel bool literals to 0/1 int vars. */
internal fun FlatZincCompiler.channelBoolsToInts(lits: IntArray, tag: String): IntArray = IntArray(lits.size) { i ->
    val ch = allocInt("__chan_${tag}_$i", 0, 1)
    val (auxVar, useNegatedTarget) = Lit.variable(lits[i]) to !Lit.isPositive(lits[i])
    factors.add(
        ReifiedLinear(
            auxBoolVar = auxVar,
            coeffs = intArrayOf(1),
            vars = intArrayOf(ch),
            op = LinearOp.EQ,
            bound = if (useNegatedTarget) 0 else 1,
        ),
    )
    ch
}

internal fun FlatZincCompiler.emitMonotoneBool(c: FznConstraint, op: MonotoneOp) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    if (lits.size < 2) return
    val ints = channelBoolsToInts(lits, "mono")
    emitMonotoneChain(ints, op)
}

private fun FlatZincCompiler.emitMonotoneChain(xs: IntArray, op: MonotoneOp) {
    val bound = if (op.strict) 1 else 0
    for (i in 0 until xs.size - 1) {
        val (a, b) = if (op.ascending) xs[i + 1] to xs[i] else xs[i] to xs[i + 1]
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b), LinearOp.GE, bound))
    }
}

internal fun FlatZincCompiler.emitLexLessBool(c: FznConstraint, strict: Boolean) {
    require(c.args.size == 2)
    val xLits = evalBoolVarArray(c.args[0])
    val yLits = evalBoolVarArray(c.args[1])
    val xs = channelBoolsToInts(xLits, "lex_x")
    val ys = channelBoolsToInts(yLits, "lex_y")
    factors.add(LexLess(xs, ys, strict))
}

internal fun FlatZincCompiler.emitTableBool(c: FznConstraint) {
    require(c.args.size == 2)
    val xLits = evalBoolVarArray(c.args[0])
    val tuplesBool = evalBoolConstArray(c.args[1])
    val xs = channelBoolsToInts(xLits, "tbl")
    val tuples = IntArray(tuplesBool.size) { if (tuplesBool[it]) 1 else 0 }
    factors.add(Table(xs, tuples))
}

internal fun FlatZincCompiler.emitMonotone(c: FznConstraint, op: MonotoneOp) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    if (xs.size < 2) return
    emitMonotoneChain(xs, op)
}
