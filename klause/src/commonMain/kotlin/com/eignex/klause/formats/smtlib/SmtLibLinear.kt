package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.constRelationHolds
import com.eignex.klause.formats.linCombDiff
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.objective.LinearObjective

// `=` is an arithmetic (integer) equality iff its operands are integer-sorted — i.e. not
// boolean. Deciding by the first operand's sort (via the scope- and ite-aware [isBoolExpr])
// is more robust than head-op matching, which misjudged an int-sorted `ite`/`let`/nested `=`
// operand as boolean and then compiled an int subterm as Bool.
internal fun SmtLibQfLia.Builder.isArithmeticRelation(t: SExpr.SList): Boolean {
    val arg = t.items.getOrNull(1) ?: return false
    return !isBoolExpr(arg)
}

/** Assert a linear relation; when all terms cancel to a constant it is trivially true (post
 *  nothing) or false (post the false literal ⇒ unsat) rather than an empty [Linear]. */
internal fun SmtLibQfLia.Builder.assertLinear(t: SExpr.SList) {
    val rel = relationToLinear(t)
    if (rel.vars.isEmpty()) {
        if (!constRelationHolds(rel.op, rel.bound)) forceTrue(Lit.negate(trueLit()))
        return
    }
    factors.add(Linear(rel.coeffs, rel.vars, rel.op, rel.bound))
}

internal fun SmtLibQfLia.Builder.reifyRelation(t: SExpr.SList): Int {
    val rel = relationToLinear(t)
    return reifyLinear(rel.coeffs, rel.vars, rel.op, rel.bound)
}

/** Lower `(op lhs rhs)` to one linear relation. */
internal fun SmtLibQfLia.Builder.relationToLinear(t: SExpr.SList): Rel {
    val op = (t.items[0] as SExpr.Atom).text
    if (t.items.size != 3) {
        throw UnsupportedSmtException(
            "$op with ${t.items.size - 1} operands not supported as a single linear relation",
        )
    }
    val (vars, coeffs, baseBound) = diff(linearTerm(t.items[1]), linearTerm(t.items[2]))
    val (linOp, delta) = when (op) {
        "<=" -> LinearOp.LE to 0
        ">=" -> LinearOp.GE to 0
        "=" -> LinearOp.EQ to 0
        "distinct" -> LinearOp.NE to 0
        "<" -> LinearOp.LE to -1
        ">" -> LinearOp.GE to 1
        else -> throw UnsupportedSmtException("relation '$op'")
    }
    return Rel(vars, coeffs, linOp, baseBound + delta)
}

internal fun SmtLibQfLia.Builder.linearTerm(t: SExpr): LinComb = unwindingLets(t) { node ->
    when (node) {
        is SExpr.Atom -> {
            val n = node.text.toLongOrNull()
            when {
                n != null -> LinComb(emptyMap(), n)

                // An integer literal beyond 64 bits (SMT integers are arbitrary precision) is not a real;
                // report it honestly as out of the lowering's range rather than as a real literal.
                isIntegerLiteral(node.text) -> throw UnsupportedSmtException(
                    "integer literal '${node.text}' exceeds the 64-bit range of the QF_LIA lowering",
                )

                isRealLiteral(
                    node.text,
                ) -> throw UnsupportedSmtException("real literal '${node.text}' (QF_LIA is integer-only)")

                else -> lookup(node.text)?.let { intBinding(node.text, it) }
                    ?: LinComb(
                        mapOf(
                            (
                                intNames[node.text]
                                    ?: throw UnsupportedSmtException("unknown int var '${node.text}'")
                                ) to 1,
                        ),
                        0,
                    )
            }
        }

        is SExpr.SList -> {
            val h = (node.items[0] as? SExpr.Atom)?.text ?: throw UnsupportedSmtException("bad int term")
            val args = node.items.drop(1)
            when (h) {
                "+" -> args.map { linearTerm(it) }.reduce(::add)

                "-" -> if (args.size == 1) {
                    scale(linearTerm(args[0]), -1L)
                } else {
                    args.drop(1).fold(linearTerm(args[0])) { acc, e -> add(acc, scale(linearTerm(e), -1L)) }
                }

                "*" -> {
                    val parts = args.map { linearTerm(it) }
                    val nonConst = parts.filter { it.coeffs.isNotEmpty() }
                    if (nonConst.size > 1) throw UnsupportedSmtException("nonlinear multiplication")
                    val k = parts.filter { it.coeffs.isEmpty() }.fold(1L) { a, c -> a * c.constant }
                    if (nonConst.isEmpty()) LinComb(emptyMap(), k) else scale(nonConst[0], k)
                }

                "to_real", "to_int" -> linearTerm(args[0])

                "/", "div", "mod", "abs" -> throw UnsupportedSmtException("nonlinear/real operator '$h'")

                "ite" -> {
                    // v = if cond then a else b: a fresh int pinned to each branch by the condition.
                    val cond = compileBool(args[0])
                    val a = linearTerm(args[1])
                    val b = linearTerm(args[2])
                    val self = LinComb(mapOf(newInt() to 1), 0)
                    factors.add(Clause(intArrayOf(Lit.negate(cond), reifyEq(self, a)))) // cond ⇒ v = a
                    factors.add(Clause(intArrayOf(cond, reifyEq(self, b)))) // ¬cond ⇒ v = b
                    self
                }

                else -> throw UnsupportedSmtException("unsupported int op '$h'")
            }
        }
    }
}

internal fun SmtLibQfLia.Builder.intBinding(name: String, b: SmtLibQfLia.Builder.Binding): LinComb {
    if (b.isBool) throw UnsupportedSmtException("'$name' used as Int but bound to a Bool term")
    return b.lin ?: throw UnsupportedSmtException("'$name' has no compiled Int value")
}

/** An integer literal — an optionally-signed run of digits, of any magnitude (SMT integers are
 *  arbitrary precision, so this includes values beyond `Int`/`Long`). */
internal fun SmtLibQfLia.Builder.isIntegerLiteral(s: String): Boolean = INTEGER_LITERAL.matches(s)

/** A real literal — a decimal with a fractional point (e.g. `2.6`), which QF_LIA does not permit. */
internal fun SmtLibQfLia.Builder.isRealLiteral(s: String): Boolean = '.' in s && s.toDoubleOrNull() != null

private val INTEGER_LITERAL = Regex("-?\\d+")

internal fun SmtLibQfLia.Builder.add(a: LinComb, b: LinComb): LinComb = a.plus(b)

internal fun SmtLibQfLia.Builder.scale(a: LinComb, k: Long): LinComb = a.scaled(k)

/** Build linear coefficients for `a - b op 0`. */
internal fun SmtLibQfLia.Builder.diff(a: LinComb, b: LinComb): Triple<IntArray, LongArray, Long> = linCombDiff(a, b)

internal fun SmtLibQfLia.Builder.linearObjective(t: SExpr, negate: Boolean): LinearObjective {
    val lt = linearTerm(t)
    val coeffs = LongArray(nextInt)
    for ((v, c) in lt.coeffs) coeffs[v] = if (negate) -c else c
    return LinearObjective(
        intCoefficients = coeffs,
        constant = if (negate) -lt.constant else lt.constant,
    )
}
