package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.IntComb
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.LinRelation
import com.eignex.klause.formats.addExact
import com.eignex.klause.formats.constRelationHolds
import com.eignex.klause.formats.intCombDiff
import com.eignex.klause.formats.linCombDiff
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.formats.wideConstHolds
import com.eignex.klause.solver.Lit
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
 *  nothing) or false (post the false literal ⇒ unsat) rather than an empty [Linear]. */
internal fun SmtLib.Builder.assertLinear(t: SExpr.SList) {
    val op = t.atomAt(0, "relation operator")
    requireBinaryRelation(t, op)
    assertRelation(op, linearTerm(t.items[1]), linearTerm(t.items[2]))
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
        is LinRelation.LongRel -> reifyLinear(rel.coeffs, rel.vars, linOp, rel.bound)

        is LinRelation.WideRel -> if (rel.vars.isEmpty()) {
            if (wideConstHolds(linOp, rel.bound)) trueLit() else Lit.negate(trueLit())
        } else {
            reifyLinear(rel.coeffs, rel.vars, linOp, rel.bound)
        }
    }
}

/** Require a relation node to have exactly two operands (`(op a b)`), else reject as unsupported. */
internal fun requireBinaryRelation(node: SExpr.SList, op: String) {
    if (node.items.size != 3) {
        throw UnsupportedSmtException(
            "$op with ${node.items.size - 1} operands not supported as a single linear relation",
        )
    }
}

/** Lower `(op lhs rhs)` to one 64-bit linear relation for OBBT bound inference, or `null` when either
 *  operand is wide (a wide relation is enforced by its factor, not used to tighten bounds). */
internal fun SmtLib.Builder.relationToLinear(t: SExpr.SList): Rel? {
    val op = t.atomAt(0, "relation operator")
    requireBinaryRelation(t, op)
    val a = linearTerm(t.items[1])
    val b = linearTerm(t.items[2])
    return if (a is IntComb.Narrow && b is IntComb.Narrow) relFromOperands(op, a.lin, b.lin) else null
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
