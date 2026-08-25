package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.ClauseWatches
import com.eignex.klause.factor.bool.internals.anyOtherLitTrue
import com.eignex.klause.factor.bool.internals.findTrueLitExcept
import com.eignex.klause.factor.bool.internals.findTrueLitExceptIndex
import com.eignex.klause.factor.bool.internals.litTrueInLsState
import com.eignex.klause.factor.bool.internals.wasLitTrueInLsState
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap

/** LS invariant for [Clause]: watched-literal violation tracking and break/make maintenance. */
internal class ClauseInvariant(
    private val boolVars: IntArray,
    private val literals: IntArray,
    private val tautological: Boolean,
) : Invariant {

    /** `boolVar → literal index` lookup; sentinel -1 for absent. Built from [literals] in init. */
    private val litIndexByVar: IntIntMap = IntIntMap.build(
        keys = IntArray(literals.size) { Lit.variable(literals[it]) },
        values = IntArray(literals.size) { it },
        absent = -1,
    )

    private fun litIndexForVar(v: Int): Int = litIndexByVar[v]

    override fun initialize(state: LocalSearchState, factorId: Int) {
        if (tautological) {
            state.intPayload[factorId] = 1
            return
        }
        val w = state.refPayload[factorId] as? ClauseWatches
            ?: ClauseWatches(0, if (literals.size > 1) 1 else -1)
        var first = -1
        var second = -1
        var trueCount = 0
        for (i in literals.indices) {
            if (litTrueInLsState(state, literals, i)) {
                trueCount++
                if (first == -1) {
                    first = i
                } else if (second == -1) {
                    second = i
                }
            }
        }
        if (first == -1) {
            w.w1 = 0
            w.w2 = if (literals.size > 1) 1 else -1
        } else if (second == -1) {
            w.w1 = first
            w.w2 = if (literals.size > 1) (if (first == 0) 1 else 0) else -1
        } else {
            w.w1 = first
            w.w2 = second
        }
        state.refPayload[factorId] = w
        state.intPayload[factorId] = trueCount
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        if (tautological) return false
        val w = state.refPayload[factorId] as ClauseWatches
        if (litTrueInLsState(state, literals, w.w1)) return false
        if (w.w2 >= 0 && litTrueInLsState(state, literals, w.w2)) return false
        return true
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (tautological) return 0
        val li = litIndexForVar(boolVar)
        if (li < 0) return 0
        val w = state.refPayload[factorId] as ClauseWatches
        val w1True = litTrueInLsState(state, literals, w.w1)
        val w2True = w.w2 >= 0 && litTrueInLsState(state, literals, w.w2)
        val wasViolated = !w1True && !w2True

        val nowViolated = when {
            w1True && w2True -> false
            w1True -> if (li != w.w1) false else !anyOtherLitTrue(state, literals, li)
            w2True -> if (li != w.w2) false else !anyOtherLitTrue(state, literals, li)
            else -> false
        }
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (tautological) return 0
        val w = state.refPayload[factorId] as ClauseWatches
        val w1WasTrue = wasLitTrueInLsState(state, literals, w.w1, boolVar)
        val w2WasTrue = if (w.w2 >= 0) wasLitTrueInLsState(state, literals, w.w2, boolVar) else false
        val wasSatisfied = w1WasTrue || w2WasTrue

        var w1NowTrue = litTrueInLsState(state, literals, w.w1)
        var w2NowTrue = if (w.w2 >= 0) litTrueInLsState(state, literals, w.w2) else false

        val li = litIndexForVar(boolVar)
        if (li >= 0) {
            val nowTrue = litTrueInLsState(state, literals, li)
            state.intPayload[factorId] += if (nowTrue) 1 else -1
        }

        if (!w1NowTrue) {
            val replacement = findTrueLitExcept(state, literals, w.w1, w.w2)
            if (replacement >= 0) {
                w.w1 = replacement
                w1NowTrue = true
            }
        }
        if (w.w2 >= 0 && !w2NowTrue && !w1NowTrue) {
            val replacement = findTrueLitExcept(state, literals, w.w2, w.w1)
            if (replacement >= 0) {
                w.w2 = replacement
                w2NowTrue = true
            }
        }

        val isSatisfied = w1NowTrue || w2NowTrue
        val nowViolated = !isSatisfied
        val wasViolated = !wasSatisfied
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (tautological) return
        if (!isViolated(state, factorId)) return
        for (v in boolVars) sink.addBoolFlip(v)
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** O(arity) — but typically O(1) — update of break/make counts after [flippedVar] is flipped.
     *
     *  Only the 0↔1 and 1↔2 transitions of `numTrueLits` change break/make contributions:
     *   - `0→1`: clause was violated; now critically sat with [flippedVar] as the critical literal.
     *   - `1→0`: critical was [flippedVar]; now violated; every var becomes a make candidate.
     *   - `1→2`: previous critical (now non-critical) loses its break.
     *   - `2→1`: the remaining true literal becomes critical and gains a break.
     *
     *  Transitions 2↔3, 3↔4, ... touch no break/make state. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        if (tautological) return
        val li = litIndexForVar(flippedVar)
        if (li < 0) return
        val newCount = state.intPayload[factorId]
        val nowTrue = litTrueInLsState(state, literals, li)
        val oldCount = if (nowTrue) newCount - 1 else newCount + 1
        when {
            oldCount == 0 && newCount == 1 -> {
                for (v in boolVars) state.boolMakeCount[v]--
                state.boolBreakCount[flippedVar]++
            }

            oldCount == 1 && newCount == 0 -> {
                state.boolBreakCount[flippedVar]--
                for (v in boolVars) state.boolMakeCount[v]++
            }

            oldCount == 1 && newCount == 2 -> {
                val oldCriticalIdx = findTrueLitExceptIndex(state, literals, li)
                if (oldCriticalIdx >= 0) {
                    state.boolBreakCount[Lit.variable(literals[oldCriticalIdx])]--
                }
            }

            oldCount == 2 && newCount == 1 -> {
                val newCriticalIdx = findTrueLitExceptIndex(state, literals, li)
                if (newCriticalIdx >= 0) {
                    state.boolBreakCount[Lit.variable(literals[newCriticalIdx])]++
                }
            }
        }
    }
}
