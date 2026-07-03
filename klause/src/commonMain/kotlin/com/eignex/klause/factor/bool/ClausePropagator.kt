package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.findNonFalseLitExcept
import com.eignex.klause.factor.bool.internals.litFalseInPropState
import com.eignex.klause.factor.bool.internals.litTrueInPropState
import com.eignex.klause.factor.bool.internals.pinUnitLit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.moveBoolWatcher
import com.eignex.klause.solver.Lit

/** CP propagator for [Clause]: two-watched-literal propagation over a disjunction of literals. */
internal class ClausePropagator(val boolVars: IntArray, val intVars: IntArray, internal val literals: IntArray) :
    Propagator {

    private var pureBoolMemo: Int = -1

    /** True iff every literal is a plain bool var (variable id `< numBoolVars`), memoised. */
    internal fun allLiteralsBool(numBoolVars: Int): Boolean {
        val m = pureBoolMemo
        if (m >= 0) return m == 1
        var allBool = true
        for (lit in literals) {
            if (Lit.variable(lit) >= numBoolVars) {
                allBool = false
                break
            }
        }
        pureBoolMemo = if (allBool) 1 else 0
        return allBool
    }

    override val initialBoolWatchers: IntArray =
        if (literals.size == 1) {
            intArrayOf(literals[0])
        } else {
            intArrayOf(literals[0], literals[1])
        }

    override val initialBoolWatcherBlockers: IntArray? =
        if (literals.size == 1) null else intArrayOf(literals[1], literals[0])

    /**
     * Two-watched-literal propagation (Zhang–Stickel / MiniSAT). Caches a pair of literal
     * indices on [PropagationState.refPayload]; each fire checks at most those two before
     * looking further. Common case (both watches non-false): returns in O(1) regardless of
     * clause arity. Slow case (a watch turned false): scans for a non-false replacement,
     * O(arity) worst-case but amortised across many fires since most flips don't disturb
     * the watches.
     *
     * Soundness across `PropagationSession` push/pop: watches deliberately aren't
     * snapshotted (see [PropagationState.refPayload] kdoc). After a pop, watches may
     * point at literals that are again unassigned at the restored level — perfectly
     * valid, since the invariant is "watches point at non-false literals" and unassigned
     * counts as non-false. If a restored state actually makes the watches stale (some
     * literal at the watch position is somehow false), the first propagate call
     * re-validates and moves them.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (literals.size == 1) {
            val lit = literals[0]
            val t = state.litTruth(lit)
            return if (t == null) {
                state.pinLit(lit, antecedents = null)
            } else {
                t
            }
        }
        val watches = (state.refPayload[factorId] as IntArray?) ?: run {
            val w = intArrayOf(0, 1)
            state.refPayload[factorId] = w
            w
        }
        if (litTrueInPropState(state, literals, watches[0]) ||
            litTrueInPropState(state, literals, watches[1])
        ) {
            return true
        }

        if (litFalseInPropState(state, literals, watches[0])) {
            val rep = findNonFalseLitExcept(state, literals, watches[0], watches[1])
            if (rep >= 0) {
                state.moveBoolWatcher(factorId, literals[watches[0]], literals[rep], literals[watches[1]])
                watches[0] = rep
            }
        }
        if (litFalseInPropState(state, literals, watches[1])) {
            val rep = findNonFalseLitExcept(state, literals, watches[1], watches[0])
            if (rep >= 0) {
                state.moveBoolWatcher(factorId, literals[watches[1]], literals[rep], literals[watches[0]])
                watches[1] = rep
            }
        }
        if (litTrueInPropState(state, literals, watches[0]) ||
            litTrueInPropState(state, literals, watches[1])
        ) {
            return true
        }

        val w0False = litFalseInPropState(state, literals, watches[0])
        val w1False = litFalseInPropState(state, literals, watches[1])
        return when {
            w0False && w1False -> false
            w0False -> pinUnitLit(state, literals, watches[1])
            w1False -> pinUnitLit(state, literals, watches[0])
            else -> true
        }
    }

    /** When [propagate] returns false, every literal was false — the textbook clause-form nogood. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray = literals
}
