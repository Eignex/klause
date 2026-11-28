package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.findNonFalseLitExcept
import com.eignex.klause.factor.bool.internals.litFalseInPropState
import com.eignex.klause.factor.bool.internals.litTrueInPropState
import com.eignex.klause.factor.bool.internals.pinUnitLit
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.LearnedPropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.moveBoolWatcher

internal class ClausePropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    internal val literals: IntArray,
) : LearnedPropagator {

    private var pureBoolMemo: Int = -1

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

    // Two-watched-literal propagation (Moskewicz et al., "Chaff: engineering an efficient SAT solver",
    // DAC 2001, https://doi.org/10.1145/378239.379017). Watches are not snapshotted across push/pop: the
    // invariant is only that they point at non-false literals, and the first propagate after a restore
    // moves a watch left on a false literal.
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (literals.size == 1) {
            val lit = literals[0]
            val t = state.litTruth(lit)
            state.settleClauseLevel(literals)
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
        if (w0False || w1False) state.settleClauseLevel(literals)
        return when {
            w0False && w1False -> false
            w0False -> pinUnitLit(state, literals, watches[1])
            w1False -> pinUnitLit(state, literals, watches[0])
            else -> true
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray = literals
}
