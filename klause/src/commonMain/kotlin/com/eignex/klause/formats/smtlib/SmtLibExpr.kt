package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.channelBoolTo01
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit

private sealed interface AssertionFrame {
    class Visit(val expression: SExpr) : AssertionFrame
    class RestoreBindings(val scope: Compiler.Builder.BindingScope) : AssertionFrame
    object PopScope : AssertionFrame
}

/** Post each conjunct of an assertion. `and`/`let` nesting is walked with an explicit worklist (not
 *  recursion) so a degenerate conjunction can't overflow the stack; relations, arithmetic equalities
 *  and `distinct` are posted as hard constraints, everything else is reified and forced true. */
internal fun Compiler.Builder.assert(top: SExpr) {
    val work = ArrayDeque<AssertionFrame>()
    work.addLast(AssertionFrame.Visit(top))
    while (work.isNotEmpty()) {
        var node = when (val frame = work.removeLast()) {
            is AssertionFrame.Visit -> frame.expression
            is AssertionFrame.PopScope -> {
                popLetScope()
                continue
            }
            is AssertionFrame.RestoreBindings -> {
                bindingScope = frame.scope
                continue
            }
        }
        var lets = 0
        while (node is SExpr.SList && (node.items.firstOrNull() as? SExpr.Atom)?.text == "let") {
            pushLetScope(node.items[1])
            lets++
            node = node.items[2]
        }
        // Pop markers go under the conjuncts, so the scope stays active while they are processed.
        repeat(lets) { work.addLast(AssertionFrame.PopScope) }
        // A `let`-bound Bool that nothing has asked for a literal yet can be asserted rather than
        // reified: substituting its term here sends it back through this loop, where `and`, the
        // comparisons and `distinct` each have a direct posting that allocates no auxiliary literal.
        // Once its literal exists the binding is shared, so a name used elsewhere is unaffected.
        if (node is SExpr.Atom) {
            val bound = lookup(node.text)
            val src = bound?.takeIf { it.isBool && it.lit == null }?.srcBool
            if (src != null) {
                val sourceScope = checkNotNull(bound.sourceScope)
                bound.srcBool = null
                bound.sourceScope = null
                bound.lit = trueLit()
                work.addLast(AssertionFrame.RestoreBindings(bindingScope))
                bindingScope = Compiler.Builder.BindingScope(sourceScope)
                work.addLast(AssertionFrame.Visit(src))
                continue
            }
        }
        if (node is SExpr.SList && node.items.isNotEmpty()) {
            val h = (node.items[0] as? SExpr.Atom)?.text
            val args = node.items.drop(1)
            when {
                h == "and" -> {
                    for (i in args.indices.reversed()) work.addLast(AssertionFrame.Visit(args[i]))
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
                    assertDistinct(args)
                    continue
                }
            }
        }
        forceTrue(compileBool(node))
    }
}

internal fun Compiler.Builder.forceTrue(lit: Int) {
    factors.add(Clause(intArrayOf(lit)))
}

internal fun Compiler.Builder.forceClause(a: Int, b: Int) {
    factors.add(Clause(intArrayOf(a, b)))
}

/** Fold [t] to a boolean literal (iteratively, via [evalTerm]). */
internal fun Compiler.Builder.compileBool(t: SExpr): Int = (evalTerm(t, Sort.BOOL) as Res.B).lit

internal fun Compiler.Builder.boolBinding(name: String, b: Compiler.Builder.Binding): Int {
    if (!b.isBool) throw UnsupportedSmtException("'$name' used as Bool but bound to an Int term")
    return boolLit(b)
}

/** Reify boolean ite as `(c and x) or (!c and y)`. */
internal fun Compiler.Builder.tseitinIte(c: Int, x: Int, y: Int): Int =
    reifyOr(listOf(reifyAnd(listOf(c, x)), reifyAnd(listOf(Lit.negate(c), y))))

/** Compile n-ary equality as pairwise equality to the first operand. */
internal fun <T> Compiler.Builder.chainEqToFirst(items: List<T>, relate: (T, T) -> Int): Int =
    reifyAnd((1 until items.size).map { relate(items[0], items[it]) })

/** Syntactic bool/int classifier for a term, following `ite`/`let` to their result term without
 *  recursion so a deeply nested chain cannot overflow the stack. */
internal fun Compiler.Builder.isBoolExpr(t: SExpr): Boolean {
    var node = t
    while (true) {
        when (node) {
            is SExpr.Atom -> {
                lookup(node.text)?.let { return it.isBool }
                val m = macros[node.text]
                if (m != null && m.params.isEmpty()) return m.isBool
                return node.text == "true" || node.text == "false" ||
                    node.text in boolNames
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

/** Channel a bool literal to a fresh 0/1 int term. */
internal fun Compiler.Builder.litToIntTerm(lit: Int): LinComb {
    val z = newInt(0L, 1L)
    val w = newBool() // w ⇔ lit
    val wlit = Lit.make(w, true)
    factors.add(Clause(intArrayOf(Lit.negate(wlit), lit)))
    factors.add(Clause(intArrayOf(wlit, Lit.negate(lit))))
    channelBoolTo01(factors, w, z)
    return LinComb(mapOf(z to 1), 0)
}

internal fun Compiler.Builder.reifyEq(a: LinComb, b: LinComb): Int = reifyRelTerms(a, b, LinearOp.EQ)

internal fun Compiler.Builder.reifyRelTerms(a: LinComb, b: LinComb, op: LinearOp): Int {
    val (vars, coeffs, bound) = diff(a, b)
    return reifyLinear(coeffs, vars, op, bound)
}
