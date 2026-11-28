package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral

// In a `≤` chain a lowered max never raises a min, so one forward lower-bound sweep and one backward
// upper-bound sweep reach bounds consistency.
internal class IncreasingPropagator(private val xs: IntArray, private val gap: Int) : Propagator {

    // Reads only bounds, so interior value removals need no wake.
    override val initialIntEventWatches: IntArray = run {
        val distinct = xs.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = state.propagatorFailures[this]

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        val d = state.intDomains
        val level = state.currentLevel

        // A bound move rests on its neighbour's bound on the same side as it stands now, which an earlier step of
        // the same sweep may just have moved.
        fun neighbour(v: Int, lower: Boolean, need: Long): IntArray? {
            if (level == 0) return null
            val lit = state.boundLiteral(v, lower, need, state.undo.size, level)
            return if (lit == Lit.NONE) IntArray(0) else intArrayOf(lit)
        }
        fun fail(ant: IntArray?, v: Int): Boolean {
            state.propagatorFailures[this] =
                (ant ?: IntArray(0)) + (collectHoleAndBoundAntecedents(state, intArrayOf(v)) ?: IntArray(0))
            return false
        }
        // Forward: xs(i).min ≥ xs(i−1).min + gap.
        for (i in 1 until xs.size) {
            val previous = d[xs[i - 1]].min
            if (gap != 0 && previous == Long.MAX_VALUE) {
                return fail(neighbour(xs[i - 1], true, previous), xs[i])
            }
            val need = previous + gap
            if (need <= d[xs[i]].min) continue
            val ant = neighbour(xs[i - 1], true, previous)
            if (!state.tightenIntMin(xs[i], need, ant)) return fail(ant, xs[i])
        }
        // Backward: xs(i).max ≤ xs(i+1).max − gap.
        for (i in xs.size - 2 downTo 0) {
            val next = d[xs[i + 1]].max
            if (gap != 0 && next == Long.MIN_VALUE) {
                return fail(neighbour(xs[i + 1], false, next), xs[i])
            }
            val cap = next - gap
            if (cap >= d[xs[i]].max) continue
            val ant = neighbour(xs[i + 1], false, next)
            if (!state.tightenIntMax(xs[i], cap, ant)) return fail(ant, xs[i])
        }
        return true
    }
}
