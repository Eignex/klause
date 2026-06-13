package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor

internal fun FlatZincCompiler.emitBoolClause(c: FznConstraint) {
    // bool_clause(pos_array, neg_array): ⋁ pos ∨ ⋁ ¬neg
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
    // a ↔ b becomes (¬a ∨ b) ∧ (a ∨ ¬b).
    factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    factors.add(Clause(intArrayOf(a, Lit.negate(b))))
}

internal fun FlatZincCompiler.emitBoolXor(c: FznConstraint) {
    require(c.args.size == 3)
    // bool_xor(a, b, c): a ⊕ b ↔ c, i.e. a ⊕ b ⊕ c = 0 — xor of all three with target parity 0.
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

internal fun FlatZincCompiler.emitArrayBoolOr(c: FznConstraint) {
    require(c.args.size == 2)
    val lits = evalBoolVarArray(c.args[0])
    val r = resolveBoolLit(c.args[1])
    // r ↔ (⋁ lits): two halves. (¬r ∨ ⋁lits) and for each lit l: (¬l ∨ r).
    factors.add(Clause(lits + intArrayOf(Lit.negate(r))))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
}

internal fun FlatZincCompiler.emitArrayBoolAnd(c: FznConstraint) {
    require(c.args.size == 2)
    val lits = evalBoolVarArray(c.args[0])
    val r = resolveBoolLit(c.args[1])
    // r ↔ (⋀ lits): (⋁ ¬lits ∨ r) and for each lit l: (¬r ∨ l).
    factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
}

/** `bool_and(a, b, r)` / `bool_or(a, b, r)` — pairwise variants, both already reified. */
internal fun FlatZincCompiler.emitBoolAndOr(c: FznConstraint, and: Boolean) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    if (and) {
        // r ↔ (a ∧ b):  (¬r ∨ a), (¬r ∨ b), (r ∨ ¬a ∨ ¬b).
        factors.add(Clause(intArrayOf(Lit.negate(r), a)))
        factors.add(Clause(intArrayOf(Lit.negate(r), b)))
        factors.add(Clause(intArrayOf(r, Lit.negate(a), Lit.negate(b))))
    } else {
        // r ↔ (a ∨ b):  (r ∨ ¬a), (r ∨ ¬b), (¬r ∨ a ∨ b).
        factors.add(Clause(intArrayOf(r, Lit.negate(a))))
        factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        factors.add(Clause(intArrayOf(Lit.negate(r), a, b)))
    }
}

/** `bool_xor_reif(a, b, r)` — r ↔ (a ⊕ b). Equivalent to `bool_xor(a, b, r)` which klause
 *  emits via the [Xor] factor with target-parity 0 over (a, b, r). */
internal fun FlatZincCompiler.emitBoolXorReif(c: FznConstraint) {
    require(c.args.size == 3)
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

/** `array_bool_xor(arr)` — parity sum of `arr` is true (i.e. an odd number of literals
 *  are true). Encodes as a single [Xor] factor with target parity 1. */
internal fun FlatZincCompiler.emitArrayBoolXor(c: FznConstraint) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    factors.add(Xor(lits, targetParity = 1))
}

/** `bool_le(a, b)`: a ≤ b ⇔ ¬a ∨ b.  `bool_lt(a, b)`: a < b ⇔ ¬a ∧ b. */
internal fun FlatZincCompiler.emitBoolCmp(c: FznConstraint, lt: Boolean, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    if (lt) {
        // a < b  ⇔  ¬a ∧ b: two unit clauses.
        factors.add(Clause(intArrayOf(Lit.negate(a))))
        factors.add(Clause(intArrayOf(b)))
    } else {
        factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    }
}

/** Reified bool comparison: `r ↔ (a ⟨op⟩ b)`. */
internal fun FlatZincCompiler.emitBoolCmpReif(c: FznConstraint, eq: Boolean, le: Boolean, lt: Boolean) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    when {
        eq -> {
            // r ↔ (a = b) ⇔ ¬(a ⊕ b). Equivalent to bool_xor(a, b, ¬r).
            factors.add(Xor(intArrayOf(a, b, Lit.negate(r)), targetParity = 0))
        }

        le -> {
            // r ↔ (a → b) ⇔ r ↔ (¬a ∨ b).
            // (¬r ∨ ¬a ∨ b), (r ∨ a), (r ∨ ¬b)
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a), b)))
            factors.add(Clause(intArrayOf(r, a)))
            factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        }

        lt -> {
            // r ↔ (¬a ∧ b).
            // r → ¬a:  (¬r ∨ ¬a)
            // r → b:   (¬r ∨ b)
            // (¬a ∧ b) → r:  (a ∨ ¬b ∨ r)
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a))))
            factors.add(Clause(intArrayOf(Lit.negate(r), b)))
            factors.add(Clause(intArrayOf(a, Lit.negate(b), r)))
        }
    }
}

internal fun FlatZincCompiler.emitBool2Int(c: FznConstraint) {
    // bool2int(b, x): b ↔ (x = 1), with x ∈ {0, 1}. We pin x to {0,1} via two unit
    // Linear bounds and encode the biconditional with a single ReifiedLinear. Polarity
    // of `b` is folded into the encoded bound: a negated bool literal (¬v ↔ x=1) is
    // rewritten as v ↔ (x = 0).
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

/**
 * `array_bool_element(idx, arr, result)` / `array_var_bool_element(...)`:
 * `result = arr(idx)` with 1-based indexing over Booleans. Routes through the native int
 * [Element] factor by channeling the Boolean operands to `[0,1]` ints — so bool element
 * gets the same graded violation + direct repair as int element (issue #45), instead of the
 * old per-index reified-linear + indicator-clause decomposition (issue #37).
 */
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

/**
 * Channel a bool literal array into a parallel int-var array with domain [0, 1] each.
 * Adds a ReifiedLinear per literal that ties `lit ↔ (channel = 1)`. Returns the new int
 * var ids in the same order. Used by the bool-variant dispatches that reuse int factors.
 */
internal fun FlatZincCompiler.channelBoolsToInts(lits: IntArray, tag: String): IntArray = IntArray(lits.size) { i ->
    val ch = allocInt("__chan_${tag}_$i", 0, 1)
    // lit ↔ (ch = 1): ReifiedLinear with auxBoolVar = variable of lit, polarity-aware.
    // For a positive literal: aux = lit's var directly.
    // For a negative literal: lit = ¬b, so b ↔ (ch = 0) which is the same as ¬b ↔ (ch = 1).
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

internal fun FlatZincCompiler.emitMonotoneBool(c: FznConstraint, ascending: Boolean, strict: Boolean) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    if (lits.size < 2) return
    val ints = channelBoolsToInts(lits, "mono")
    emitMonotoneChain(ints, ascending, strict)
}

/** (in/de)creasing(xs) → adjacent Linear comparisons. Ascending non-strict `xs(i+1)-xs(i) ≥ 0`,
 *  strict `≥ 1`; descending swaps the pair. Bounds propagation on the chain is complete. */
private fun FlatZincCompiler.emitMonotoneChain(xs: IntArray, ascending: Boolean, strict: Boolean) {
    val bound = if (strict) 1 else 0
    for (i in 0 until xs.size - 1) {
        val (a, b) = if (ascending) xs[i + 1] to xs[i] else xs[i] to xs[i + 1]
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

internal fun FlatZincCompiler.emitMonotone(c: FznConstraint, ascending: Boolean, strict: Boolean) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    if (xs.size < 2) return // 0- or 1-element array is trivially monotone.
    emitMonotoneChain(xs, ascending, strict)
}
