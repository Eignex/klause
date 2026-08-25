package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.formats.IntComb
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.LinRelation
import com.eignex.klause.formats.addExact
import com.eignex.klause.formats.intCombDiff
import com.eignex.klause.formats.linCombDiff
import com.eignex.klause.formats.wideConstHolds
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.constRelationHolds
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.solver.objective.LinearObjective

// `=` is an arithmetic (integer) equality iff its operands are integer-sorted — i.e. not
// boolean. Deciding by the first operand's sort (via the scope- and ite-aware [isBoolExpr])
// is more robust than head-op matching, which misjudged an int-sorted `ite`/`let`/nested `=`
// operand as boolean and then compiled an int subterm as Bool.
internal fun SmtLib.Builder.isArithmeticRelation(t: SExpr.SList): Boolean {
    val arg = t.items.getOrNull(1) ?: return false
    return !isBoolExpr(arg)
}

/** Assert a linear relation; when all terms cancel to a constant it is trivially true (post
 *  nothing) or false (post the false literal ⇒ unsat) rather than an empty [Linear]. An n-ary chain
 *  `(op a1 … an)` is the conjunction of its n−1 consecutive relations, per the SMT-LIB chainable
 *  semantics — `a1 op a2 ∧ … ∧ a(n−1) op an`, with no direct relation between non-adjacent operands. */
internal fun SmtLib.Builder.assertLinear(t: SExpr.SList) {
    val op = t.atomAt(0, "relation operator")
    requireChainableRelation(t, op)
    // Fold each operand once: a term with a side-effecting subterm (`ite`, `div`, …) allocates fresh
    // variables and clauses per fold, and an interior operand takes part in two consecutive relations.
    val terms = (1 until t.items.size).map { linearTerm(t.items[it]) }
    if (postOrderChain(op, terms)) return
    for (i in 0 until terms.size - 1) assertRelation(op, terms[i], terms[i + 1])
}

/** Post an order chain over three or more bare integer variables as one [Increasing], returning whether
 *  it applied. A two-operand relation is already a single exact row, and a repeated variable would make
 *  the chain propagate one unit per wake, so both decline to the pairwise [Linear] rows. */
private fun SmtLib.Builder.postOrderChain(op: String, terms: List<IntComb>): Boolean {
    if (terms.size < 3) return false
    val descending = when (op) {
        "<", "<=" -> false
        ">", ">=" -> true
        else -> return false
    }
    val vars = IntArray(terms.size) { i ->
        val narrow = terms[i] as? IntComb.Narrow ?: return false
        narrow.lin.asSimpleVar() ?: return false
    }
    if (vars.toHashSet().size != vars.size) return false
    // No theory holds an [Increasing], so an open column in the chain would leave the model with no owner
    // for it. The pairwise rows the caller falls back to are difference constraints a theory decides, so
    // declining costs nothing — the same trade the open `distinct` makes.
    if (vars.any { intDomains[it] is PresolveDomain.Open }) return false
    // `a ≥ b ≥ c` is the ascending chain over the reversed sequence; [Increasing] only represents ascending.
    factors.add(Increasing(if (descending) vars.reversedArray() else vars, strict = strictDelta(op) != 0))
    return true
}

/** Post a linear row, or resolve a variable-free relation to trivially-true (post nothing) or
 *  false (post the false literal ⇒ unsat) — never an empty [Linear]. Shared by the assert and the
 *  reified-relation paths. */
internal fun SmtLib.Builder.assertLinearRow(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) {
    if (vars.isEmpty()) {
        if (!constRelationHolds(op, bound)) forceTrue(Lit.negate(trueLit()))
    } else {
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

/** Assert `a ⟨op⟩ b` (an SMT relation operator) as a hard linear row, lowering to a wide [Linear]
 *  when a coefficient or the bound exceeds the 64-bit range. */
internal fun SmtLib.Builder.assertRelation(op: String, a: IntComb, b: IntComb) {
    val linOp = relLinearOp(op)
    when (val rel = intCombDiff(a, b, strictDelta(op).toLong())) {
        is LinRelation.LongRel -> assertLinearRow(rel.coeffs, rel.vars, linOp, rel.bound)

        is LinRelation.WideRel -> if (rel.vars.isEmpty()) {
            if (!wideConstHolds(linOp, rel.bound)) forceTrue(Lit.negate(trueLit()))
        } else {
            factors.add(Linear(rel.vars, rel.coeffs, linOp, rel.bound))
        }
    }
}

/** Reify `a ⟨op⟩ b` onto a fresh literal, using a wide [com.eignex.klause.factor.arithmetic.ReifiedLinear]
 *  when a coefficient or the bound exceeds the 64-bit range. */
internal fun SmtLib.Builder.reifyRelation(op: String, a: IntComb, b: IntComb): Int {
    val linOp = relLinearOp(op)
    return when (val rel = intCombDiff(a, b, strictDelta(op).toLong())) {
        is LinRelation.LongRel -> reifyLinear(rel.coeffs, rel.vars, linOp, rel.bound).also {
            noteEqAtom(it, linOp, rel.coeffs, rel.vars, rel.bound)
        }

        is LinRelation.WideRel -> if (rel.vars.isEmpty()) {
            if (wideConstHolds(linOp, rel.bound)) trueLit() else Lit.negate(trueLit())
        } else {
            reifyLinear(rel.coeffs, rel.vars, linOp, rel.bound)
        }
    }
}

/** Record `lit ⇔ variable = value` when the reified row is exactly that, so an `ite` chain can read its
 *  condition back off the literal. Only a finite-domain variable is recorded: a chain selector needs a
 *  known index range, and an open one could never index an array. */
private fun SmtLib.Builder.noteEqAtom(lit: Int, op: LinearOp, coeffs: LongArray, vars: IntArray, bound: Long) {
    if (op != LinearOp.EQ || vars.size != 1) return
    val c = coeffs[0]
    if (c != 1L && c != -1L) return
    if (intDomains[vars[0]] !is PresolveDomain.Finite) return
    iteChains.noteAtom(lit, vars[0], bound * c)
}

/** Require a chainable relation node to have at least two operands (`(op a b …)`), else reject. */
internal fun requireChainableRelation(node: SExpr.SList, op: String) {
    if (node.items.size < 3) {
        throw UnsupportedSmtException("$op with ${node.items.size - 1} operands is not a relation")
    }
}

/** Collect the 64-bit linear relations of `(op a1 … an)` — one per consecutive pair — for source bound
 *  inference, skipping a pair with a wide operand (a wide relation is enforced by its factor). */
internal fun SmtLib.Builder.relationToLinear(t: SExpr.SList, out: MutableList<Rel>) {
    val op = t.atomAt(0, "relation operator")
    requireChainableRelation(t, op)
    val terms = (1 until t.items.size).map { linearTerm(t.items[it]) }
    for (i in 0 until terms.size - 1) {
        val a = terms[i]
        val b = terms[i + 1]
        if (a is IntComb.Narrow && b is IntComb.Narrow) out.add(relFromOperands(op, a.lin, b.lin))
    }
}

/** Build a linear relation `a op b` (as `Σ coeffs·vars ⟨op⟩ bound`) from two folded operands. */
internal fun SmtLib.Builder.relFromOperands(op: String, a: LinComb, b: LinComb): Rel {
    val (vars, coeffs, baseBound) = diff(a, b)
    val (linOp, delta) = when (op) {
        "<=" -> LinearOp.LE to 0
        ">=" -> LinearOp.GE to 0
        "=" -> LinearOp.EQ to 0
        "distinct" -> LinearOp.NE to 0
        "<" -> LinearOp.LE to -1
        ">" -> LinearOp.GE to 1
        else -> throw UnsupportedSmtException("relation '$op'")
    }
    return Rel(vars, coeffs, linOp, foldChecked { addExact(baseBound, delta.toLong()) })
}

/** Run [block], surfacing a folded-term 64-bit overflow as a clean [UnsupportedSmtException].
 *  SMT integers are unbounded, so an overflow means the term exceeds what the solver represents. */
internal inline fun <T> foldChecked(block: () -> T): T = try {
    block()
} catch (_: ArithmeticException) {
    throw UnsupportedSmtException("integer overflow while folding a linear term")
}

/** Fold [t] to an integer linear combination (iteratively, via [evalTerm]); [IntComb.Wide] when a value
 *  exceeds the 64-bit range. */
internal fun SmtLib.Builder.linearTerm(t: SExpr): IntComb = (evalTerm(t, Sort.INT) as Res.I).term

/** Fold [t] to a 64-bit integer combination, rejecting a wide value — for the narrow-only consumers
 *  (the objective and `distinct`/`AllDifferent`) that have no arbitrary-precision path. */
internal fun SmtLib.Builder.linearTermNarrow(t: SExpr): LinComb = when (val ic = linearTerm(t)) {
    is IntComb.Narrow -> ic.lin
    is IntComb.Wide -> throw UnsupportedSmtException("integer beyond the 64-bit range in this context")
}

internal fun SmtLib.Builder.intBinding(name: String, b: SmtLib.Builder.Binding): IntComb {
    if (b.isBool) throw UnsupportedSmtException("'$name' used as Int but bound to a Bool term")
    return b.lin ?: throw UnsupportedSmtException("'$name' has no compiled Int value")
}

/** An integer literal — an optionally-signed non-empty run of ASCII digits, of any magnitude (SMT
 *  integers are arbitrary precision, so this includes values beyond `Int`/`Long`). Scanned explicitly
 *  rather than via a `\d` regex, whose Unicode-digit semantics are not guaranteed identical across KMP
 *  targets. */
internal fun SmtLib.Builder.isIntegerLiteral(s: String): Boolean {
    val start = if (s.startsWith('-')) 1 else 0
    if (start >= s.length) return false
    for (i in start until s.length) if (s[i] !in '0'..'9') return false
    return true
}

/** A real literal — a decimal with a fractional point (e.g. `2.6`), which QF_LIA does not permit. */
internal fun SmtLib.Builder.isRealLiteral(s: String): Boolean = '.' in s && s.toDoubleOrNull() != null

/** Build linear coefficients for `a - b op 0`. */
internal fun SmtLib.Builder.diff(a: LinComb, b: LinComb): Triple<IntArray, LongArray, Long> =
    foldChecked { linCombDiff(a, b) }

internal fun SmtLib.Builder.linearObjective(t: SExpr, negate: Boolean): LinearObjective {
    val lt = linearTermNarrow(t)
    val coeffs = LongArray(nextInt)
    for ((v, c) in lt.coeffs) coeffs[v] = if (negate) -c else c
    return LinearObjective(
        intCoefficients = coeffs,
        constant = if (negate) -lt.constant else lt.constant,
    )
}
