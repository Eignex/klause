package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS contract for [Xor]: parity violation tracking and break/make maintenance. */
interface XorInvariant : Invariant {

    /** The literals whose parity is constrained. */
    val literals: IntArray

    /** Required parity (0 = even, 1 = odd). */
    val targetParity: Int

    /** Parity contribution of [v]: `(occurrences mod 2)`. Returns 0 if [v] is not in [literals]. */
    fun parityOf(v: Int): Int

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var parity = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) parity = parity xor 1
        }
        state.intPayload[factorId] = parity
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.intPayload[factorId] != targetParity

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val parity = state.intPayload[factorId]
        val newParity = parity xor parityOf(boolVar)
        val wasViolated = parity != targetParity
        val willViolate = newParity != targetParity
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldParity = state.intPayload[factorId]
        val newParity = oldParity xor parityOf(boolVar)
        state.intPayload[factorId] = newParity
        val wasViolated = oldParity != targetParity
        val nowViolated = newParity != targetParity
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == targetParity) return
        for (v in boolVars) {
            if (parityOf(v) == 1) sink.addBoolFlip(v)
        }
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** O(arity) — but typically the loop body is one branch. The parity model collapses
     *  to: if the flipped var's parity contribution is 0, nothing changed; otherwise
     *  violation flips and every parity-contributing var swaps break↔make. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        if (parityOf(flippedVar) == 0) return
        val newParity = state.intPayload[factorId]
        val nowViolated = newParity != targetParity
        if (nowViolated) {
            for (v in boolVars) {
                if (parityOf(v) == 0) continue
                state.boolBreakCount[v]--
                state.boolMakeCount[v]++
            }
        } else {
            for (v in boolVars) {
                if (parityOf(v) == 0) continue
                state.boolMakeCount[v]--
                state.boolBreakCount[v]++
            }
        }
    }
}
