package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `value_precede(s, t, xs)` (#432): value [t] may appear in [xs] only at a position after value [s]
 * has appeared — i.e. the first occurrence of [s] precedes the first occurrence of [t] (or [t] never
 * occurs). The building block of `value_precede_chain` (one per consecutive value pair) and of the
 * Law–Lee value-symmetry break (#374).
 *
 * Native GAC propagator, replacing the per-index reified-equality + clause prefix-OR decomposition
 * (which was only sub-GAC). The constraint is "no [t] before the first feasible [s]", so GAC is an
 * O(n) scan:
 *  - **Prune [t] early.** Let `α` be the first index where [s] is still possible. No position `≤ α`
 *    can be [t] (nothing before it can be [s]); prune [t] there. If [s] is impossible everywhere,
 *    [t] is impossible everywhere.
 *  - **Force [s] before a forced [t].** If some position is fixed to [t], [s] must occur strictly
 *    before the *earliest* such position; if exactly one position before it can still be [s], fix it
 *    to [s]. (After the prune, `α` is always one such candidate, so a forced [t] with no possible
 *    preceding [s] is a conflict.)
 *
 * Only [t] is ever removed and only the unique pre-forced-[t] candidate is fixed to [s] — [s] is
 * never forbidden and other values are never touched, which is exactly GAC for this constraint.
 * Value precedence is pure symmetry breaking, so there is no LP relaxation — propagation only.
 */
class ValuePrecede(override val s: Int, override val t: Int, override val xs: IntArray) :
    Factor,
    ValuePrecedePropagator,
    ValuePrecedeInvariant {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ValuePrecede(s, t, xs.remapVars(intMap))

    // Positional: the sequence order decides "before", so xs is not sorted. Encodes the values and
    // the full var sequence — collision-free up to variable identity (sound for symmetry checks).
    override fun structuralKey(): String = "vprec:$s:$t:" + xs.joinToString(",")

    /** Relabel the two named values (#374 value-symmetry verification): `value_precede(s,t)` maps to
     *  `value_precede(π(s), π(t))` under a value permutation π. */
    override fun remapValues(valueMap: (Int) -> Int): Factor = ValuePrecede(valueMap(s), valueMap(t), xs)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Advisor subscription (#623): membership-sensitive (the prefix scan tests `s ∈ dom` and
     *  forced-`t`), so subscribe to every kind on every sequence variable and consume the dirty-
     *  variable delta (#624). The reversible `α`/`prunedUpTo` state ([VpState]) advances only over the
     *  changed prefix instead of rescanning the whole sequence each fire. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = xs.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = true
}
