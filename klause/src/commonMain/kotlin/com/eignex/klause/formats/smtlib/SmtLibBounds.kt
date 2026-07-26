package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong

/** Bound inference for the SMT-LIB front-end: a fixpoint over the conjunctive linear relations tightens
 *  each integer variable's `[lo, hi]`. A bound that stays unprovable is left `null` (infinite) — infinity
 *  is structural, never a `±Long/4` sentinel — so the result is a [PresolveDomain] (finite or still-open). */
internal fun SmtLibQfLia.Builder.inferBounds() {
    if (intNames.isEmpty()) return
    // null = unbounded on that side (-inf for lo, +inf for hi); no sentinel magnitude.
    val lo = arrayOfNulls<Long>(nextInt)
    val hi = arrayOfNulls<Long>(nextInt)
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
                // sLo/sHi accumulate the min/max of Σ_{other} c·x; null once that direction is infinite
                // (an infinite contributing bound or an overflow — both mean "no finite bound derivable").
                var sLo: Long? = 0L
                var sHi: Long? = 0L
                for (oi in r.vars.indices) {
                    if (oi == ti) continue
                    val c = r.coeffs[oi]
                    val v = r.vars[oi]
                    if (sLo != null) {
                        val b = if (c >= 0) lo[v] else hi[v]
                        sLo = if (b == null) null else mulAdd(sLo, c, b)
                    }
                    if (sHi != null) {
                        val b = if (c >= 0) hi[v] else lo[v]
                        sHi = if (b == null) null else mulAdd(sHi, c, b)
                    }
                }
                val bnd = r.bound
                if (r.op == LinearOp.LE || r.op == LinearOp.EQ) {
                    changed = applyCtBound(lo, hi, tv, ct, sLo?.let { subOrNull(bnd, it) }, upper = true) || changed
                }
                if (r.op == LinearOp.GE || r.op == LinearOp.EQ) {
                    changed = applyCtBound(lo, hi, tv, ct, sHi?.let { subOrNull(bnd, it) }, upper = false) || changed
                }
            }
        }
    }

    for ((name, v) in intNames) {
        val provLo = lo[v]
        val provHi = hi[v]
        if (strictBounds && (provLo == null || provHi == null)) {
            throw UnsupportedSmtException("no provable ${if (provLo == null) "lower" else "upper"} bound for '$name'")
        }
        // A still-null side stays Open for OBBT; the searchable-fallback clamp (and the `unknown`
        // downgrade) is owned by finalizeDomains, not here.
        intDomains[v] = openOrFinite(provLo, provHi)
    }
}

// lo/hi are nullable (a null slot is ±infinity), so a primitive LongArray cannot represent them.
@Suppress("ArrayPrimitive")
internal fun SmtLibQfLia.Builder.applyCtBound(
    lo: Array<Long?>,
    hi: Array<Long?>,
    tv: Int,
    ct: Long,
    rhs: Long?,
    upper: Boolean,
): Boolean {
    if (rhs == null) return false
    // ct>0 with an upper target (or ct<0 with a lower one) yields a floor bound on hi[tv]; the mirror
    // case yields a ceil bound on lo[tv].
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

/** `acc + c·x`, or null if either the multiply or the add overflows (treated as an infinite bound).
 *  Shared with the aux-variable range arithmetic in `SmtLibEval.kt` — infinity is `null`, never a sentinel. */
internal fun mulAdd(acc: Long, c: Long, x: Long): Long? {
    if (c != 0L) {
        val p = c * x
        if (p / c != x) return null
        val s = acc + p
        return if (((acc xor s) and (p xor s)) < 0) null else s
    }
    return acc
}

/** `a - b`, or null on overflow. */
internal fun subOrNull(a: Long, b: Long): Long? {
    val d = a - b
    return if (((a xor b) and (a xor d)) < 0) null else d
}

/** `a + b`, or null on overflow. */
internal fun addOrNull(a: Long, b: Long): Long? {
    val s = a + b
    return if (((a xor s) and (b xor s)) < 0) null else s
}

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

private const val MAX_BOUND_ITERS = 64

