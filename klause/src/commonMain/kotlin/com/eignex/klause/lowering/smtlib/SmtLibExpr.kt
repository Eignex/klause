package com.eignex.klause.lowering.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.allDifferentWindow
import com.eignex.klause.lowering.channelBoolTo01
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinOr
import com.eignex.klause.solver.IntDomain

/** Post each conjunct of an assertion. `and`/`let` nesting is walked with an explicit worklist (not
 *  recursion) so a degenerate conjunction can't overflow the stack; relations, arithmetic equalities
 *  and `distinct` are posted as hard constraints, everything else is reified and forced true. */
internal fun SmtLib.Builder.assert(top: SExpr) {
    // Worklist of pending assertion nodes; a `null` entry is a let-scope pop marker.
    val work = ArrayDeque<SExpr?>()
    work.addLast(top)
    while (work.isNotEmpty()) {
        val item = work.removeLast()
        if (item == null) {
            popLetScope()
            continue
        }
        var node: SExpr = item
        var lets = 0
        while (node is SExpr.SList && (node.items.firstOrNull() as? SExpr.Atom)?.text == "let") {
            pushLetScope(node.items[1])
            lets++
            node = node.items[2]
        }
        // Pop markers go under the conjuncts, so the scope stays active while they are processed.
        repeat(lets) { work.addLast(null) }
        // A `let`-bound Bool that nothing has asked for a literal yet can be asserted rather than
        // reified: substituting its term here sends it back through this loop, where `and`, the
        // comparisons and `distinct` each have a direct posting that allocates no auxiliary literal.
        // Once its literal exists the binding is shared as before, so a name used elsewhere is unaffected.
        if (node is SExpr.Atom) {
            val bound = lookup(node.text)
            val src = bound?.takeIf { it.isBool && it.lit == null }?.srcBool
            if (src != null) {
                bound.srcBool = null
                bound.lit = trueLit()
                work.addLast(src)
                continue
            }
        }
        if (node is SExpr.SList && node.items.isNotEmpty()) {
            val h = (node.items[0] as? SExpr.Atom)?.text
            val args = node.items.drop(1)
            when {
                h == "and" -> {
                    for (i in args.indices.reversed()) work.addLast(args[i])
                    continue
                }

                // An asserted disjunction *is* a clause. Reifying it would allocate an auxiliary literal,
                // the implication clauses that define it and a unit clause forcing it true, where posting
                // the disjuncts directly states the same thing. The disjuncts themselves still reify as
                // needed; only the top node is saved. Empty `(or)` is false and left to the general path.
                h == "or" && args.isNotEmpty() -> {
                    factors.add(Clause(IntArray(args.size) { compileBool(args[it]) }))
                    continue
                }

                // `=>` is n-ary and right-associative: `(=> a1 .. an)` is `a1 -> (.. -> an)`, which holds
                // exactly when some antecedent fails or the consequent holds. Asserting that is the clause
                // `!a1 or .. or !a(n-1) or an`, where reifying it would build the implication chain first
                // and then force its literal true. A one-argument `=>` is just its argument.
                h == "=>" && args.size >= 2 -> {
                    val lits = IntArray(args.size) { compileBool(args[it]) }
                    for (k in 0 until lits.size - 1) lits[k] = Lit.negate(lits[k])
                    factors.add(Clause(lits))
                    continue
                }

                h == "<=" || h == "<" || h == ">=" || h == ">" -> {
                    if (isRealRelation(node)) assertRealLinear(node) else assertLinear(node)
                    continue
                }

                h == "=" && isArithmeticRelation(node) && args.size >= 2 -> {
                    if (isRealRelation(node)) assertRealLinear(node) else assertLinear(node)
                    continue
                }

                h == "distinct" -> {
                    if (!isRealRelation(node)) {
                        assertDistinct(args)
                        continue
                    }
                }
            }
        }
        forceTrue(compileBool(node))
    }
}

internal fun SmtLib.Builder.forceTrue(lit: Int) {
    factors.add(Clause(intArrayOf(lit)))
}

internal fun SmtLib.Builder.forceClause(a: Int, b: Int) {
    factors.add(Clause(intArrayOf(a, b)))
}

/** Fold [t] to a boolean literal (iteratively, via [evalTerm]). */
internal fun SmtLib.Builder.compileBool(t: SExpr): Int = (evalTerm(t, Sort.BOOL) as Res.B).lit

internal fun SmtLib.Builder.boolBinding(name: String, b: SmtLib.Builder.Binding): Int {
    if (!b.isBool) throw UnsupportedSmtException("'$name' used as Bool but bound to an Int term")
    return boolLit(b)
}

/** Reify boolean ite as `(c and x) or (!c and y)`. */
internal fun SmtLib.Builder.tseitinIte(c: Int, x: Int, y: Int): Int =
    tseitinOr(listOf(tseitinAnd(listOf(c, x)), tseitinAnd(listOf(Lit.negate(c), y))))

/** Compile n-ary equality as pairwise equality to the first operand. */
internal fun <T> SmtLib.Builder.chainEqToFirst(items: List<T>, relate: (T, T) -> Int): Int =
    tseitinAnd((1 until items.size).map { relate(items[0], items[it]) })

/** Syntactic bool/int classifier for a term, following `ite`/`let` to their result term without
 *  recursion so a deeply nested chain cannot overflow the stack. */
internal fun SmtLib.Builder.isBoolExpr(t: SExpr): Boolean {
    var node = t
    while (true) {
        when (node) {
            is SExpr.Atom -> {
                val m = macros[node.text]
                if (m != null && m.params.isEmpty()) return m.isBool
                return node.text == "true" || node.text == "false" ||
                    node.text in boolNames || (lookup(node.text)?.isBool == true)
            }

            is SExpr.SList -> when (val head = (node.items.firstOrNull() as? SExpr.Atom)?.text) {
                "and", "or", "not", "=>", "xor", "=", "distinct", "<", "<=", ">", ">=" -> return true
                "+", "-", "*", "to_real", "to_int", "abs", "div", "mod" -> return false
                "ite" -> node = node.argAt(2, "ite then branch")
                "let" -> node = node.argAt(2, "let body")
                else -> return macros[head]?.isBool ?: false
            }
        }
    }
}

internal fun SmtLib.Builder.assertDistinct(args: List<SExpr>) {
    if (args.size < 2) return
    if (args.all { !isBoolExpr(it) }) {
        val terms = args.map { linearTermNarrow(it) }
        val simpleVars = terms.mapNotNull { it.asSimpleVar() }
        if (simpleVars.size == terms.size && simpleVars.toSet().size == simpleVars.size) {
            val vars = simpleVars.toIntArray()
            val window = allDifferentWindow(vars, { finiteDomainOf(it)?.min }, { finiteDomainOf(it)?.max })
            if (window != null) {
                factors.add(AllDifferent(vars = vars, domainMin = window.min, domainSize = window.size))
            } else {
                assertPairwiseStrictOrder(terms)
            }
        } else {
            assertPairwiseNe(terms)
        }
    } else {
        assertPairwiseNe(args.map { if (isBoolExpr(it)) litToIntTerm(compileBool(it)) else linearTermNarrow(it) })
    }
}

/** The declared domain of [variable] when both its sides are known, else null — a column still open on
 *  either side has no window a value-indexed global can address. */
private fun SmtLib.Builder.finiteDomainOf(variable: Int): IntDomain? =
    (intDomains[variable] as? PresolveDomain.Finite)?.domain

/** Post pairwise `!=` as linear NE constraints. */
internal fun SmtLib.Builder.assertPairwiseNe(terms: List<LinComb>) {
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) factors.add(neLinear(terms[i], terms[j]))
    }
}

/** Post every integer disequality as the Boolean-theory choice `a < b ∨ a > b`. */
private fun SmtLib.Builder.assertPairwiseStrictOrder(terms: List<LinComb>) {
    for (i in terms.indices) {
        for (j in i + 1 until terms.size) {
            val left = IntComb.Narrow(terms[i])
            val right = IntComb.Narrow(terms[j])
            val less = reifyRelation("<", left, right)
            val greater = reifyRelation(">", left, right)
            factors.add(Clause(intArrayOf(less, greater)))
        }
    }
}

/** Channel a bool literal to a fresh 0/1 int term. */
internal fun SmtLib.Builder.litToIntTerm(lit: Int): LinComb {
    val z = newInt(0L, 1L)
    val w = newBool() // w ⇔ lit
    val wlit = Lit.make(w, true)
    factors.add(Clause(intArrayOf(Lit.negate(wlit), lit)))
    factors.add(Clause(intArrayOf(wlit, Lit.negate(lit))))
    channelBoolTo01(factors, w, z)
    return LinComb(mapOf(z to 1), 0)
}

internal fun SmtLib.Builder.neLinear(a: LinComb, b: LinComb): Linear {
    val (vars, coeffs, bound) = diff(a, b)
    return Linear(coeffs, vars, LinearOp.NE, bound)
}

internal fun SmtLib.Builder.reifyNe(a: LinComb, b: LinComb): Int = reifyRelTerms(a, b, LinearOp.NE)
internal fun SmtLib.Builder.reifyEq(a: LinComb, b: LinComb): Int = reifyRelTerms(a, b, LinearOp.EQ)

internal fun SmtLib.Builder.reifyRelTerms(a: LinComb, b: LinComb, op: LinearOp): Int {
    val (vars, coeffs, bound) = diff(a, b)
    return reifyLinear(coeffs, vars, op, bound)
}
