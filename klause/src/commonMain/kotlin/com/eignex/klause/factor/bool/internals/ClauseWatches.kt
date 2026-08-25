package com.eignex.klause.factor.bool.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.PropagationState

/** Mutable two-watch index pair for a clause. Stored in [LocalSearchState.refPayload] and
 *  also shared by the CP path via [PropagationState.refPayload]. */
internal class ClauseWatches(var w1: Int, var w2: Int)

/** True iff `literals[idx]` evaluates to true under [state]'s CP assignment. */
internal fun litTrueInPropState(state: PropagationState, literals: IntArray, idx: Int): Boolean =
    state.litTrue(literals[idx])

/** True iff `literals[idx]` evaluates to false under [state]'s CP assignment. */
internal fun litFalseInPropState(state: PropagationState, literals: IntArray, idx: Int): Boolean =
    state.litFalse(literals[idx])

/** Find an index in [literals] (other than [excludeA] and [excludeB]) that is not currently
 *  false in [state]. Returns -1 if none exists. */
internal fun findNonFalseLitExcept(state: PropagationState, literals: IntArray, excludeA: Int, excludeB: Int): Int {
    for (i in literals.indices) {
        if (i == excludeA || i == excludeB) continue
        if (!litFalseInPropState(state, literals, i)) return i
    }
    return -1
}

/** Unit-propagate `literals[unitIdx]` to true, with every other literal as antecedent. */
internal fun pinUnitLit(state: PropagationState, literals: IntArray, unitIdx: Int): Boolean {
    val unitLit = literals[unitIdx]
    val antecedents: IntArray? = if (literals.size <= 1) {
        null
    } else {
        val out = IntArray(literals.size - 1)
        var w = 0
        for (i in literals.indices) if (i != unitIdx) out[w++] = literals[i]
        out
    }
    return state.pinLit(unitLit, antecedents)
}

/** True iff `literals[idx]` is true under the LS assignment. */
internal fun litTrueInLsState(state: LocalSearchState, literals: IntArray, idx: Int): Boolean {
    if (idx < 0) return false
    val lit = literals[idx]
    return Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))
}

/** Pre-flip evaluation: value of `literals[idx]` reconstructed from the post-flip assignment.
 *  When the literal's variable is [flippedVar], the pre-flip value is the negation of current. */
internal fun wasLitTrueInLsState(state: LocalSearchState, literals: IntArray, idx: Int, flippedVar: Int): Boolean {
    if (idx < 0) return false
    val lit = literals[idx]
    val v = Lit.variable(lit)
    val post = state.assignment.boolValue(v)
    val pre = if (v == flippedVar) !post else post
    return Lit.evaluate(lit, pre)
}

/** True iff some literal at an index other than [excludeIdx] is currently true in [state]. */
internal fun anyOtherLitTrue(state: LocalSearchState, literals: IntArray, excludeIdx: Int): Boolean {
    for (i in literals.indices) {
        if (i == excludeIdx) continue
        if (litTrueInLsState(state, literals, i)) return true
    }
    return false
}

/** Find a literal index (other than [exclude1] and [exclude2]) that evaluates true in [state]. */
internal fun findTrueLitExcept(state: LocalSearchState, literals: IntArray, exclude1: Int, exclude2: Int): Int {
    for (i in literals.indices) {
        if (i == exclude1 || i == exclude2) continue
        if (litTrueInLsState(state, literals, i)) return i
    }
    return -1
}

/** Find a literal index other than [excludeIdx] that evaluates true in [state]. */
internal fun findTrueLitExceptIndex(state: LocalSearchState, literals: IntArray, excludeIdx: Int): Int {
    for (i in literals.indices) {
        if (i == excludeIdx) continue
        if (litTrueInLsState(state, literals, i)) return i
    }
    return -1
}
