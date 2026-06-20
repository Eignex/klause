package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.arithmetic.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt

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

    /** Reversible incremental state. [alpha] is the first index where [s] is still possible — it only
     *  ever advances forward as [s] is removed from early positions (a backtrack restores it via the
     *  trail). [prunedUpTo] records that Rule A has already pruned [t] from positions `[0, prunedUpTo)`,
     *  so each fire prunes only the newly-covered suffix. [started] gates the first full pass. */
    private class VpState(state: PropagationState) {
        var started: Boolean = false
        val alpha = RevInt(state, 0)
        val prunedUpTo = RevInt(state, 0)
    }

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

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: relabel one position in a way that cannot create a
     *  `t`-before-first-`s` violation. Safe targets are (a) the value `s` itself — adding/moving an
     *  `s` only pulls the first-`s` index earlier, never breaking precedence — and (b) any value
     *  other than `t`, provided the position isn't currently the guarding `s` (so we never strip the
     *  `s` that a later `t` depends on). This frees the variable for a coupled constraint while the
     *  precedence stays satisfied. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (xs.isEmpty()) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(xs.size)
            val v = state.assignment.intValue(xs[i])
            val d = state.problem.intDomains[xs[i]]
            var pick = -1
            var seen = 0
            d.forEach { w ->
                if (w != v && (w == s || (w != t && v != s))) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = w
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, xs[i], pick)
            emitted++
        }
    }

    /** Feasible init: a `t`-free assignment (each position takes any in-domain value other than
     *  `t`), so `t` never occurs and precedence holds trivially. Returns false — leaving the random
     *  assignment — when a position can only be `t` (frozen to `t`, or singleton `{t}` domain). */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in xs.indices) {
            val v = xs[i]
            if (state.assumptions.isFrozenInt(v)) {
                if (state.assignment.intValue(v) == t) return false
                continue
            }
            val d = state.problem.intDomains[v]
            var pick = -1
            d.forEach { if (pick < 0 && it != t) pick = it }
            if (pick < 0) return false
            state.assignment.setInt(v, pick)
        }
        return true
    }

    private companion object {
        /** Cap on precedence-preserving relabel moves offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_MOVE_CAP: Int = 4

        /** Rejection-sampling attempts per requested move before giving up. */
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }

    /** Hole-aware conflict reason — the current domains of [xs] (bound shifts + interior holes) the
     *  deduction reasons over; identical helper to the hole-pruning globals (AllDifferent, Table). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        if (n == 0) return true
        val st = (state.refPayload[factorId] as? VpState) ?: run {
            val fresh = VpState(state)
            state.refPayload[factorId] = fresh
            fresh
        }
        // Fast path (#624): a fire that drains an empty dirty-variable delta saw no change since the
        // last pass, so α / the prunes are still at their fixpoint. The first fire always runs.
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (st.started && dirty.isEmpty()) return true
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
        // α: first index where s is still possible (n if s is impossible everywhere). Monotone — it
        // only advances as s is removed from early positions — so resume from the reversible cell.
        var alpha = st.alpha.value
        while (alpha < n && s !in state.intDomains[xs[alpha]]) alpha++
        if (alpha != st.alpha.value) st.alpha.set(alpha)
        // Rule A: no position ≤ α can be t. (If s is impossible everywhere, α = n ⇒ t pruned all.)
        // Prune only the suffix not yet covered by an earlier fire's Rule A.
        val upTo = if (alpha == n) n - 1 else alpha
        for (j in st.prunedUpTo.value..upTo) {
            val v = xs[j]
            if (t in state.intDomains[v] && !state.excludeIntValue(v, t, reason())) return false
        }
        if (upTo + 1 > st.prunedUpTo.value) st.prunedUpTo.set(upTo + 1)
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
        st.started = true
        return true
    }
}
