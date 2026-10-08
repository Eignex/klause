package com.eignex.klause.factor.bool

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
internal class ClauseInvariant private constructor(private val clause: Clause? = null) : Invariant {

    private val litIndexByVar: IntIntMap? = clause?.literals?.let { literals ->
        IntIntMap.build(
            keys = IntArray(literals.size) { Lit.variable(literals[it]) },
            values = IntArray(literals.size) { it },
            absent = -1,
        )
    }

    private fun clause(state: LocalSearchState, factorId: Int): Clause =
        clause ?: state.problem.factors[factorId] as Clause

    private fun litIndexForVar(literals: IntArray, v: Int): Int {
        litIndexByVar?.let { return it[v] }
        for (i in literals.indices) if (Lit.variable(literals[i]) == v) return i
        return -1
    }

    private fun packWatches(first: Int, second: Int): Long =
        (second.toLong() shl 32) or (first.toLong() and 0xFFFFFFFFL)

    companion object {
        private const val LINEAR_SCAN_LIMIT = 4
        private val small = ClauseInvariant()

        // Small clauses need only the immutable model data and the state's packed watch slots.
        fun of(clause: Clause): ClauseInvariant =
            if (clause.literals.size <= LINEAR_SCAN_LIMIT || clause.tautological) small else ClauseInvariant(clause)
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val clause = clause(state, factorId)
        val literals = clause.literals
        if (clause.tautological) {
            state.intPayload[factorId] = 1
            return
        }
        val w1: Int
        val w2: Int
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
            w1 = 0
            w2 = if (literals.size > 1) 1 else -1
        } else if (second == -1) {
            w1 = first
            w2 = if (literals.size > 1) (if (first == 0) 1 else 0) else -1
        } else {
            w1 = first
            w2 = second
        }
        state.longPayload[factorId] = packWatches(w1, w2)
        state.intPayload[factorId] = trueCount
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val clause = clause(state, factorId)
        val literals = clause.literals
        if (clause.tautological) return false
        val watches = state.longPayload[factorId]
        val w1 = watches.toInt()
        val w2 = (watches ushr 32).toInt()
        if (litTrueInLsState(state, literals, w1)) return false
        if (w2 >= 0 && litTrueInLsState(state, literals, w2)) return false
        return true
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val clause = clause(state, factorId)
        val literals = clause.literals
        if (clause.tautological) return 0
        val li = litIndexForVar(literals, boolVar)
        if (li < 0) return 0
        val watches = state.longPayload[factorId]
        val w1 = watches.toInt()
        val w2 = (watches ushr 32).toInt()
        val w1True = litTrueInLsState(state, literals, w1)
        val w2True = w2 >= 0 && litTrueInLsState(state, literals, w2)
        val wasViolated = !w1True && !w2True

        val nowViolated = when {
            w1True && w2True -> false
            w1True -> if (li != w1) false else !anyOtherLitTrue(state, literals, li)
            w2True -> if (li != w2) false else !anyOtherLitTrue(state, literals, li)
            else -> false
        }
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val clause = clause(state, factorId)
        val literals = clause.literals
        if (clause.tautological) return 0
        val watches = state.longPayload[factorId]
        var w1 = watches.toInt()
        var w2 = (watches ushr 32).toInt()
        val w1WasTrue = wasLitTrueInLsState(state, literals, w1, boolVar)
        val w2WasTrue = if (w2 >= 0) wasLitTrueInLsState(state, literals, w2, boolVar) else false
        val wasSatisfied = w1WasTrue || w2WasTrue

        var w1NowTrue = litTrueInLsState(state, literals, w1)
        var w2NowTrue = if (w2 >= 0) litTrueInLsState(state, literals, w2) else false

        val li = litIndexForVar(literals, boolVar)
        if (li >= 0) {
            val nowTrue = litTrueInLsState(state, literals, li)
            state.intPayload[factorId] += if (nowTrue) 1 else -1
        }

        if (!w1NowTrue) {
            val replacement = findTrueLitExcept(state, literals, w1, w2)
            if (replacement >= 0) {
                w1 = replacement
                w1NowTrue = true
            }
        }
        if (w2 >= 0 && !w2NowTrue && !w1NowTrue) {
            val replacement = findTrueLitExcept(state, literals, w2, w1)
            if (replacement >= 0) {
                w2 = replacement
                w2NowTrue = true
            }
        }

        state.longPayload[factorId] = packWatches(w1, w2)
        val isSatisfied = w1NowTrue || w2NowTrue
        val nowViolated = !isSatisfied
        val wasViolated = !wasSatisfied
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val clause = clause(state, factorId)
        if (clause.tautological) return
        if (!isViolated(state, factorId)) return
        for (v in clause.boolVars) sink.addBoolFlip(v)
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
        val clause = clause(state, factorId)
        val literals = clause.literals
        if (clause.tautological) return
        val li = litIndexForVar(literals, flippedVar)
        if (li < 0) return
        val newCount = state.intPayload[factorId]
        val nowTrue = litTrueInLsState(state, literals, li)
        val oldCount = if (nowTrue) newCount - 1 else newCount + 1
        when {
            oldCount == 0 && newCount == 1 -> {
                for (v in clause.boolVars) state.boolMakeCount[v]--
                state.boolBreakCount[flippedVar]++
            }

            oldCount == 1 && newCount == 0 -> {
                state.boolBreakCount[flippedVar]--
                for (v in clause.boolVars) state.boolMakeCount[v]++
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
