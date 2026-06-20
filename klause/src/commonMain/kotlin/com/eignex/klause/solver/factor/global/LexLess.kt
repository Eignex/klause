package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `lex_less(xs, ys)` / `lex_lesseq(xs, ys)` — lexicographic ordering on equal-length int
 * vectors. [strict] = `true` for strict less-than, `false` for less-or-equal.
 *
 *  - Strict: `xs <ₗₑₓ ys`  iff  there exists `k` with `xs`k` < ys`k`` and `xs`i` = ys`i``
 *    for all `i < k`.
 *  - Non-strict: `xs ≤ₗₑₓ ys`  iff  the strict version holds *or* `xs`i` = ys`i`` for all `i`.
 *
 * If `xs.size != ys.size` the shorter array is treated as a prefix: a proper prefix
 * compares strictly less than the longer one (MiniZinc semantics).
 *
 * LS recomputes the relation on each query.
 */
class LexLess(
    /** Left vector variable ids. */
    override val xs: IntArray,
    /** Right vector variable ids, parallel to [xs]. */
    override val ys: IntArray,
    /** When true the relation is strict (`xs < ys`); otherwise `xs ≤ ys`. */
    override val strict: Boolean,
) : Factor,
    LexLessPropagator,
    LexLessInvariant {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        LexLess(xs.remapVars(intMap), ys.remapVars(intMap), strict)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    /**
     * Advisor subscription (#623): lexicographic propagation is bound-only (see [propagate], which
     * compares `min`/`max` at the deciding position and tightens bounds — its own comment notes it
     * "can't propagate further with bound-only reasoning"). An interior hole moves no bound, so the
     * factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips
     * interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }
}
