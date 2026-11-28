package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.util.collections.Bits
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.addExactOrNull
import com.eignex.util.numbers.mulExactOrNull

// Single-variable conjunctive relations only; multi-variable fixpoint tightening belongs to budgeted presolve.
// The bounds justify finite auxiliary ranges without imposing bounds on open declarations.
internal fun Compiler.Builder.inferBounds() {
    if (intNames.isEmpty()) return
    val lo = Array<Long?>(nextInt) { v ->
        when (val domain = intDomains[v]) {
            is PresolveDomain.Finite -> domain.domain.min
            is PresolveDomain.Open -> domain.lo
        }
    }
    val hi = Array<Long?>(nextInt) { v ->
        when (val domain = intDomains[v]) {
            is PresolveDomain.Finite -> domain.domain.max
            is PresolveDomain.Open -> domain.hi
        }
    }
    val relations = boundPremises.apply { clear() }
    inferringBounds = true
    try {
        for (a in asserts) collectConjunctiveRelations(a, relations)
    } finally {
        inferringBounds = false
    }

    for (r in relations) {
        if (r.op == LinearOp.NE) continue
        var only = -1
        var terms = 0
        for (i in r.vars.indices) {
            if (r.coeffs[i] != 0L) {
                terms++
                only = i
            }
        }
        if (terms != 1) continue
        val v = r.vars[only]
        val c = r.coeffs[only]
        if (r.op == LinearOp.LE || r.op == LinearOp.EQ) applyCtBound(lo, hi, v, c, r.bound, upper = true)
        if (r.op == LinearOp.GE || r.op == LinearOp.EQ) applyCtBound(lo, hi, v, c, r.bound, upper = false)
    }

    for ((name, v) in intNames) {
        val provLo = lo[v]
        val provHi = hi[v]
        if (strictBounds && (provLo == null || provHi == null)) {
            throw UnsupportedSmtException("no provable ${if (provLo == null) "lower" else "upper"} bound for '$name'")
        }
        intDomains[v] = openOrFinite(provLo, provHi)
    }
}

internal fun Compiler.Builder.hasOpenProductOperand(): Boolean {
    if (intDomains.none { it is PresolveDomain.Open }) return false
    val pending = ArrayDeque<Pair<SExpr, Boolean>>()
    val operands = ArrayDeque<ProductOperand>()
    for (assertion in asserts) pending.addLast(assertion to false)
    while (pending.isNotEmpty()) {
        if (sourceCancellation()) throw PolynomialSourceInterrupted()
        val (node, visited) = pending.removeLast()
        when (node) {
            is SExpr.Atom -> operands.addLast(ProductOperand(
                constant = node.text.firstOrNull()?.isDigit() == true,
                open = intNames[node.text]?.let { intDomains[it] is PresolveDomain.Open } == true,
            ))
            is SExpr.SList -> {
                if (!visited) {
                    pending.addLast(node to true)
                    for (child in node.items.drop(1)) pending.addLast(child to false)
                    continue
                }
                val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                val children = List((node.items.size - 1).coerceAtLeast(0)) { operands.removeLast() }
                val open = children.any { it.open }
                val nonlinear = when (head) {
                    "*" -> children.count { !it.constant } > 1
                    "^" -> (node.items.getOrNull(2) as? SExpr.Atom)?.text !in setOf("0", "1")
                    else -> false
                }
                if (nonlinear && open) return true
                operands.addLast(ProductOperand(
                    constant = head in setOf("+", "-", "*", "/", "^") && children.all { it.constant },
                    open = open,
                ))
            }
        }
    }
    return false
}

private class ProductOperand(val constant: Boolean, val open: Boolean)

internal class SmtLibRefutedPremises(val premises: Problem) : RuntimeException()

internal fun Compiler.Builder.closeProductBounds() {
    if (productBoundsClosed || intDomains.none { it is PresolveDomain.Open }) return
    productBoundsClosed = true
    sourceCancellation.polynomialSourceCheckpoint()
    val premises = Problem(settings = settings, numBoolVars = 0, intBounds = modelIntBounds(),
        factors = Array(boundPremises.size) { index ->
            val row = boundPremises[index]
            ExactLinear.of(IntArray(row.vars.size) { Term.ofIntVar(row.vars[it]) },
                row.coeffs.map { BigFraction.ofLong(it) }, row.op, BigFraction.ofLong(row.bound))
        })
    val outcome = premises.closeOpenBounds(sourceCancellation)
    sourceCancellation.polynomialSourceCheckpoint()
    val closed = when (outcome) {
        OpenPresolveResult.Refuted -> throw SmtLibRefutedPremises(premises)
        is OpenPresolveResult.Tightened -> outcome.spec
    }
    for (id in intDomains.indices) {
        if (intDomains[id] !is PresolveDomain.Open) continue
        intDomains[id] = openOrFinite(
            if (closed.intBounds.hasLower(id)) closed.intBounds.lower(id) else null,
            if (closed.intBounds.hasUpper(id)) closed.intBounds.upper(id) else null,
        )
    }
}

internal fun Compiler.Builder.modelIntBounds(): IntBounds {
    val lower = LongArray(nextInt)
    val upper = LongArray(nextInt)
    var openLo: Bits? = null
    var openHi: Bits? = null
    for (v in 0 until nextInt) {
        when (val domain = intDomains[v]) {
            is PresolveDomain.Finite -> {
                lower[v] = domain.domain.min
                upper[v] = domain.domain.max
            }

            is PresolveDomain.Open -> {
                lower[v] = domain.lo ?: 0L
                upper[v] = domain.hi ?: 0L
                if (domain.lo == null) (openLo ?: Bits(nextInt).also { openLo = it }).set(v)
                if (domain.hi == null) (openHi ?: Bits(nextInt).also { openHi = it }).set(v)
            }
        }
    }
    return IntBounds.fromModelBounds(lower, upper, openLo, openHi)
}

// A null slot is ±infinity, which a primitive LongArray cannot represent.
@Suppress("ArrayPrimitive")
internal fun Compiler.Builder.applyCtBound(
    lo: Array<Long?>,
    hi: Array<Long?>,
    tv: Int,
    ct: Long,
    rhs: Long?,
    upper: Boolean,
): Boolean {
    if (rhs == null) return false
    return if ((ct > 0) == upper) {
        val b = floorDivLong(rhs, ct)
        val cur = hi[tv]
        if (cur == null || b < cur) {
            hi[tv] = b
            true
        } else {
            false
        }
    } else {
        val b = ceilDivLong(rhs, ct)
        val cur = lo[tv]
        if (cur == null || b > cur) {
            lo[tv] = b
            true
        } else {
            false
        }
    }
}

// Overflow yields null, read as an infinite bound.
internal fun mulAdd(acc: Long, c: Long, x: Long): Long? {
    val product = mulExactOrNull(c, x) ?: return null
    return addExactOrNull(acc, product)
}

internal fun Compiler.Builder.collectConjunctiveRelations(top: SExpr, out: ArrayList<Rel>) {
    val work = ArrayDeque<SExpr>()
    work.addLast(top)
    while (work.isNotEmpty()) {
        val t = work.removeLast()
        if (t !is SExpr.SList || t.items.isEmpty()) continue
        when ((t.items[0] as? SExpr.Atom)?.text) {
            "and" -> for (i in 1 until t.items.size) work.addLast(t.items[i])

            "or" -> equalityDisjunctionBounds(t, out)

            "<=", "<", ">=", ">", "=" -> if (t.items.size >= 3 && isArithmeticRelation(t) && !hasSideEffectingTerm(t)) {
                try {
                    relationToLinear(t, out)
                } catch (_: UnsupportedSmtException) { }
            }
        }
    }
}

// `(or (= x k₁) … (= x kₙ))` bounds `x` by its least and greatest constant; the lowering still posts the
// disjunction for the holes. One disjunct saying anything else leaves it satisfiable outside the set.
private fun Compiler.Builder.equalityDisjunctionBounds(t: SExpr.SList, out: ArrayList<Rel>) {
    if (t.items.size < 2) return
    var v = -1
    var min = Long.MAX_VALUE
    var max = Long.MIN_VALUE
    for (i in 1 until t.items.size) {
        val d = t.items[i]
        if (d !is SExpr.SList || d.items.size < 3) return
        if ((d.items[0] as? SExpr.Atom)?.text != "=") return
        if (!isArithmeticRelation(d) || hasSideEffectingTerm(d)) return
        val eq = ArrayList<Rel>(1)
        try {
            relationToLinear(d, eq)
        } catch (_: UnsupportedSmtException) {
            return
        }
        // A chained `(= x y z)` lowers to several rows; only a lone `x = k` states a value for x.
        val r = eq.singleOrNull() ?: return
        if (r.op != LinearOp.EQ || r.vars.size != 1 || r.coeffs[0] != 1L) return
        if (v == -1) {
            v = r.vars[0]
        } else if (v != r.vars[0]) {
            return
        }
        if (r.bound < min) min = r.bound
        if (r.bound > max) max = r.bound
    }
    if (v == -1) return
    out.add(Rel(intArrayOf(v), longArrayOf(1L), LinearOp.GE, min))
    out.add(Rel(intArrayOf(v), longArrayOf(1L), LinearOp.LE, max))
}

// Lowering such a subterm allocates variables, which the bound arrays of the read-only inference are not sized
// for. A `define-fun` body may contain any of them.
internal fun Compiler.Builder.hasSideEffectingTerm(t: SExpr): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(t)
    while (work.isNotEmpty()) {
        val n = work.removeLast()
        if (n is SExpr.SList) {
            val head = (n.items.firstOrNull() as? SExpr.Atom)?.text
            if (head == "ite" || head == "abs" || head == "div" || head == "mod" || head == "to_int" ||
                (head != null && macros.containsKey(head))
            ) {
                return true
            }
            for (c in n.items) work.addLast(c)
        }
    }
    return false
}
