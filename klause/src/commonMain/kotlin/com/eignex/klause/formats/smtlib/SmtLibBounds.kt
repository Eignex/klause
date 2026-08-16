package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong

/**
 * The bounds a single scan of the conjunctive relations yields: for each integer variable, the tightest
 * `[lo, hi]` implied by the relations that mention it *alone* (`x ≤ 7`, `3·x = 12`, …). A side no such
 * relation pins is left `null` (infinite) — infinity is structural, never a `±Long/4` sentinel — so the
 * result is a [PresolveDomain] (finite or still-open).
 *
 * Deliberately NOT a fixpoint. Deducing a bound from a multi-variable row is feasibility-based bound
 * tightening, and running it here would duplicate what the root bake and the deferred
 * [com.eignex.klause.lp.DeferredIntBounds] run already do over the lowered rows — except that parsing has
 * no wall-clock budget to bound it with, which is what hung the Petri-net and concurrency QF_LIA
 * instances. This pass is O(Σ row width) once, the same order as reading the document.
 *
 * What stays here is only what the *lowering* needs before it can run: an unbounded operand widens an
 * `ite`/`div`/`mod` auxiliary's range and drops `distinct` from an [com.eignex.klause.factor.global.AllDifferent]
 * to pairwise `≠`. Everything else is left to presolve, which tightens under its budget.
 */
internal fun SmtLib.Builder.inferBounds() {
    if (intNames.isEmpty()) return
    // null = unbounded on that side (-inf for lo, +inf for hi); no sentinel magnitude.
    val lo = arrayOfNulls<Long>(nextInt)
    val hi = arrayOfNulls<Long>(nextInt)
    val relations = ArrayList<Rel>()
    for (a in asserts) collectConjunctiveRelations(a, relations)

    for (r in relations) {
        if (r.op == LinearOp.NE) continue
        // Single-term rows only. With no other term the rest-sum is 0, so the bound is the rhs itself.
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
        // A still-null side stays Open for OBBT; the searchable-fallback clamp (and the `unknown`
        // downgrade) is owned by finalizeDomains, not here.
        intDomains[v] = openOrFinite(provLo, provHi)
    }
}

// lo/hi are nullable (a null slot is ±infinity), so a primitive LongArray cannot represent them.
@Suppress("ArrayPrimitive")
internal fun SmtLib.Builder.applyCtBound(
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

/** `a + b`, or null on overflow. */
internal fun addOrNull(a: Long, b: Long): Long? {
    val s = a + b
    return if (((a xor s) and (b xor s)) < 0) null else s
}

internal fun SmtLib.Builder.collectConjunctiveRelations(top: SExpr, out: ArrayList<Rel>) {
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
                    relationToLinear(t)?.let { out.add(it) }
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
internal fun SmtLib.Builder.hasSideEffectingTerm(t: SExpr): Boolean {
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
