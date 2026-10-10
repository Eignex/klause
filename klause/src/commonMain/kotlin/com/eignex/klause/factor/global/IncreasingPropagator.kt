package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral

/**
 * CP propagator for [Increasing]: bounds consistency on the chain `xs(0) (+gap) ≤ xs(1) …` via a
 * forward lower-bound sweep then a backward upper-bound sweep. The chain is Berge-acyclic, so these
 * two O(n) passes reach the same fixpoint a pairwise [com.eignex.klause.factor.arithmetic.Linear]
 * decomposition would — full bounds-consistency, no global algorithm gains anything — with one factor
 * and one wake instead of n−1. Lower bounds flow forward and upper bounds flow backward independently
 * (in a `≤` chain a lowered max never raises a min, nor vice versa), so a single pass each suffices.
 */
internal class IncreasingPropagator(private val xs: IntArray, private val gap: Int) : Propagator {

    /** Reads only `min`/`max`, so subscribe to bound events and skip interior `VALUE_REMOVED` wakes. */
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
        // Forward: xs(i).min ≥ xs(i−1).min + gap. Each tighten feeds the next iteration, so the
        // prefix maximum propagates in one pass; a failed tighten (min crosses max) is the conflict.
        for (i in 1 until xs.size) {
            val need = d[xs[i - 1]].min + gap
            if (need <= d[xs[i]].min) continue
            val ant = neighbour(xs[i - 1], true, need - gap)
            if (!state.tightenIntMin(xs[i], need, ant)) return fail(ant, xs[i])
        }
        // Backward: xs(i).max ≤ xs(i+1).max − gap.
        for (i in xs.size - 2 downTo 0) {
            val cap = d[xs[i + 1]].max - gap
            if (cap >= d[xs[i]].max) continue
            val ant = neighbour(xs[i + 1], false, cap + gap)
            if (!state.tightenIntMax(xs[i], cap, ant)) return fail(ant, xs[i])
        }
        return true
    }
}
