package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.IntDomain

/** Bound inference for the SMT-LIB front-end: a fixpoint over the conjunctive linear relations tightens
 *  each integer variable's `[lo, hi]`, falling back to / clamping into the configured unbounded range. */
internal fun SmtLibQfLia.Builder.inferBounds() {
    if (intNames.isEmpty()) return
    val lo = LongArray(nextInt) { NEG_INF }
    val hi = LongArray(nextInt) { POS_INF }
    val relations = ArrayList<Rel>()
    for (a in asserts) collectConjunctiveRelations(a, relations)

    var changed = true
    var iter = 0
    while (changed && iter++ < MAX_BOUND_ITERS) {
        changed = false
        for (r in relations) {
            if (r.op == LinearOp.NE) continue
            for (ti in r.vars.indices) {
                val tv = r.vars[ti]
                val ct = r.coeffs[ti]
                if (ct == 0L) continue
                var sLo = 0L
                var sHi = 0L
                var sLoInf = false
                var sHiInf = false
                for (oi in r.vars.indices) {
                    if (oi == ti) continue
                    val c = r.coeffs[oi]
                    val v = r.vars[oi]
                    val vlo = lo[v]
                    val vhi = hi[v]
                    val (clo, chi) = if (c >= 0) {
                        c * safe(vlo) to c * safe(vhi)
                    } else {
                        c * safe(vhi) to c * safe(vlo)
                    }
                    if (c >= 0) {
                        if (vlo <= NEG_INF) sLoInf = true
                        if (vhi >= POS_INF) sHiInf = true
                    } else {
                        if (vhi >= POS_INF) sLoInf = true
                        if (vlo <= NEG_INF) sHiInf = true
                    }
                    sLo += clo
                    sHi += chi
                }
                val bnd = r.bound
                if ((r.op == LinearOp.LE || r.op == LinearOp.EQ) && !sLoInf) {
                    changed = applyCtBound(lo, hi, tv, ct, bnd - sLo, upper = true) || changed
                }
                if ((r.op == LinearOp.GE || r.op == LinearOp.EQ) && !sHiInf) {
                    changed = applyCtBound(lo, hi, tv, ct, bnd - sHi, upper = false) || changed
                }
            }
        }
    }

    val loCap = unboundedIntLo.toLong()
    val hiCap = unboundedIntHi.toLong()
    for ((name, v) in intNames) {
        val provLo = lo[v]
        val provHi = hi[v]
        var vlo = provLo
        var vhi = provHi
        if (vlo <= NEG_INF) {
            if (strictBounds) throw UnsupportedSmtException("no provable lower bound for '$name'")
            vlo = loCap
        }
        if (vhi >= POS_INF) {
            if (strictBounds) throw UnsupportedSmtException("no provable upper bound for '$name'")
            vhi = hiCap
        }
        val clo = vlo.coerceIn(loCap, hiCap)
        val chi = vhi.coerceIn(loCap, hiCap)
        // The finite domain drops part of the variable's true range when a bound was unprovable
        // (infinite) or a provable bound lay outside the representable range and was clamped. Any such
        // narrowing makes an `unsat` result unsound for the original problem — it is only `unsat`
        // within this finite box — so flag it to downgrade the verdict to `unknown`. A `clo > chi`
        // (empty domain) is *not* clamping: `coerceIn` is monotonic, so it only arises when the
        // provable bounds already contradict — a genuine `unsat` that must stay `unsat`.
        if (provLo <= NEG_INF || provHi >= POS_INF || provLo < loCap || provHi > hiCap) {
            domainsClamped = true
        }
        intDomains[v] = if (clo <= chi) IntDomain(clo, chi) else IntDomain(clo, clo)
    }
}

internal fun SmtLibQfLia.Builder.applyCtBound(
    lo: LongArray,
    hi: LongArray,
    tv: Int,
    ct: Long,
    rhs: Long,
    upper: Boolean,
): Boolean {
    var changed = false
    if (ct > 0) {
        if (upper) {
            val b = floorDiv(
                rhs,
                ct,
            )
            if (b < hi[tv]) {
                hi[tv] = b
                changed = true
            }
        } else {
            val b = ceilDiv(
                rhs,
                ct,
            )
            if (b > lo[tv]) {
                lo[tv] = b
                changed = true
            }
        }
    } else {
        if (upper) {
            val b = ceilDiv(
                rhs,
                ct,
            )
            if (b > lo[tv]) {
                lo[tv] = b
                changed = true
            }
        } else {
            val b = floorDiv(
                rhs,
                ct,
            )
            if (b < hi[tv]) {
                hi[tv] = b
                changed = true
            }
        }
    }
    return changed
}

internal fun SmtLibQfLia.Builder.safe(x: Long): Long = x.coerceIn(NEG_INF, POS_INF)

internal fun SmtLibQfLia.Builder.collectConjunctiveRelations(top: SExpr, out: ArrayList<Rel>) {
    // Walk the `and` conjunction with an explicit worklist (not recursion), so a degenerate
    // conjunction can't overflow the stack during bound inference.
    val work = ArrayDeque<SExpr>()
    work.addLast(top)
    while (work.isNotEmpty()) {
        val t = work.removeLast()
        if (t !is SExpr.SList || t.items.isEmpty()) continue
        when ((t.items[0] as? SExpr.Atom)?.text) {
            "and" -> for (i in 1 until t.items.size) work.addLast(t.items[i])

            "<=", "<", ">=", ">", "=" -> if (t.items.size == 3 && isArithmeticRelation(t) && !hasSideEffectingTerm(t)) {
                try {
                    out.add(relationToLinear(t))
                } catch (_: UnsupportedSmtException) { }
            }
        }
    }
}

/** Whether [t] contains a subterm whose lowering has side effects (fresh vars and clauses): `ite`,
 *  `abs`, `div`, `mod`, or a `define-fun` call (whose body may contain any of these). The read-only
 *  bound-inference pass must not descend into such a relation, since evaluating it would allocate
 *  variables the fixpoint's bound arrays aren't sized for. The scan is iterative (explicit stack) so
 *  a deep term can't overflow the call stack. */
internal fun SmtLibQfLia.Builder.hasSideEffectingTerm(t: SExpr): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(t)
    while (work.isNotEmpty()) {
        val n = work.removeLast()
        if (n is SExpr.SList) {
            val head = (n.items.firstOrNull() as? SExpr.Atom)?.text
            if (head == "ite" || head == "abs" || head == "div" || head == "mod" ||
                (head != null && macros.containsKey(head))
            ) {
                return true
            }
            for (c in n.items) work.addLast(c)
        }
    }
    return false
}

internal const val NEG_INF = Long.MIN_VALUE / 4
internal const val POS_INF = Long.MAX_VALUE / 4
private const val MAX_BOUND_ITERS = 64

/** Pure-Kotlin floor/ceil division for multiplatform builds. */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return (if ((a xor b) < 0 && q * b != a) q - 1 else q).coerceIn(NEG_INF, POS_INF)
}
private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    return (if ((a xor b) > 0 && q * b != a) q + 1 else q).coerceIn(NEG_INF, POS_INF)
}
