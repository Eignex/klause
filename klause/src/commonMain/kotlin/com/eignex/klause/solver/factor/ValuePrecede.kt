package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

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
class ValuePrecede(val s: Int, val t: Int, val xs: IntArray) : Factor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ValuePrecede(s, t, xs.remapVars(intMap))

    // Positional: the sequence order decides "before", so xs is not sorted. Encodes the values and
    // the full var sequence — collision-free up to variable identity (sound for symmetry checks).
    override fun structuralKey(): String = "vprec:$s:$t:" + xs.joinToString(",")

    /** Relabel the two named values (#374 value-symmetry verification): `value_precede(s,t)` maps to
     *  `value_precede(π(s), π(t))` under a value permutation π. */
    override fun remapValues(valueMap: (Int) -> Int): Factor = ValuePrecede(valueMap(s), valueMap(t), xs)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Index of the first `xs` position whose current value is [s] or [t], or `-1` if none — the
     *  position that decides the constraint. */
    private fun firstStOccurrence(state: LocalSearchState): Int {
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (v == s || v == t) return i
        }
        return -1
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val i = firstStOccurrence(state)
        return i >= 0 && state.assignment.intValue(xs[i]) == t
    }

    /** Graded violation: the number of [t]-occurrences that precede the first [s] (0 iff satisfied).
     *  A move that retires an early [t], or introduces an [s] ahead of them, lowers the count. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(badTCount(state, intVar = -1, newValue = 0).toLong(), state.violationSoftCap)

    /** Count of positions holding [t] before the first [s], with [intVar] hypothetically set to
     *  [newValue] (no override when `intVar < 0`). */
    private fun badTCount(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var count = 0
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (v == s) return count // first s reached — later t's are fine
            if (v == t) count++
        }
        return count
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cap = state.violationSoftCap
        val after = compressViolation(badTCount(state, intVar, newValue).toLong(), cap)
        val before = compressViolation(badTCount(state, -1, 0).toLong(), cap)
        return after - before
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair: at the first [t] that precedes the first [s], either move that [t] off [t], or set an
     *  earlier position (or that one) to [s] so the precedence is restored. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        var firstS = xs.size
        var firstBadT = -1
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (v == s) {
                firstS = i
                break
            }
            if (v == t && firstBadT < 0) firstBadT = i
        }
        if (firstBadT < 0 || firstBadT > firstS) return // satisfied
        // (a) Move the offending t to any other value its domain allows.
        val badVar = xs[firstBadT]
        val badDom = state.problem.intDomains[badVar]
        for (cand in intArrayOf(badDom.min, badDom.max)) {
            if (cand != t && cand in badDom) {
                sink.addChannelingIntSet(state, badVar, cand)
                break
            }
        }
        // (b) Introduce s at the earliest position that can take it, up to the offending t.
        for (i in 0..firstBadT) {
            val v = xs[i]
            if (s in state.problem.intDomains[v] && state.assignment.intValue(v) != s) {
                sink.addChannelingIntSet(state, v, s)
                break
            }
        }
    }

    /** Hole-aware conflict reason — the current domains of [xs] (bound shifts + interior holes) the
     *  deduction reasons over; identical helper to the hole-pruning globals (AllDifferent, Table). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        if (n == 0) return true
        // α: first index where s is still possible (n if s is impossible everywhere).
        var alpha = n
        for (i in 0 until n) {
            if (s in state.intDomains[xs[i]]) {
                alpha = i
                break
            }
        }
        // One shared start-of-call reason: the deductions all rest on the s-positions / forced-t in
        // the pre-prune domains, so a single hole/bound snapshot soundly justifies each.
        var reason: IntArray? = null
        var reasonReady = false
        fun reason(): IntArray? {
            if (!reasonReady) {
                reason = collectHoleAndBoundAntecedents(state, xs)
                reasonReady = true
            }
            return reason
        }
        // Rule A: no position ≤ α can be t. (If s is impossible everywhere, α = n ⇒ t pruned all.)
        val upTo = if (alpha == n) n - 1 else alpha
        for (j in 0..upTo) {
            val v = xs[j]
            if (t in state.intDomains[v] && !state.excludeIntValue(v, t, reason())) return false
        }
        // Rule B: s must occur before the earliest position fixed to t.
        var firstForcedT = -1
        for (j in 0 until n) {
            val d = state.intDomains[xs[j]]
            if (d.min == d.max && d.min == t) {
                firstForcedT = j
                break
            }
        }
        if (firstForcedT >= 0) {
            var candidate = -1
            var count = 0
            for (k in 0 until firstForcedT) {
                if (s in state.intDomains[xs[k]]) {
                    candidate = k
                    count++
                    if (count > 1) break
                }
            }
            if (count == 0) return false // forced t with no possible preceding s
            if (count == 1) {
                val v = xs[candidate]
                if (!state.tightenIntMin(v, s, reason())) return false
                if (!state.tightenIntMax(v, s, reason())) return false
            }
        }
        return true
    }
}
