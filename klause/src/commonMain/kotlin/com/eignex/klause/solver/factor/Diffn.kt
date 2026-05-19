package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `diffn(xs, ys, widths, heights)` — pairwise non-overlapping 2D rectangles. Each rectangle
 * `i` has lower-left corner `(xs[i], ys[i])` and dimensions `(widths[i], heights[i])`.
 * Two rectangles non-overlap iff they're disjoint on at least one axis.
 *
 * [nonStrict] = true treats zero-width / zero-height rectangles as never overlapping
 * (the `diffn_nonstrict` variant). With [nonStrict] = false (the default), even zero-
 * dimensional rectangles must satisfy the non-overlap criterion.
 *
 * Widths and heights are constants; positions are variables. Propagation in this first cut
 * checks the all-singleton case for pairwise overlap. Cumulative-style sweep over each
 * axis lands when full propagator strength is in scope (next step).
 */
class Diffn(
    val xs: IntArray,
    val ys: IntArray,
    val widths: IntArray,
    val heights: IntArray,
    val nonStrict: Boolean = false,
) : LocalSearchFactor {

    init {
        require(xs.size == ys.size) { "diffn: xs/ys size mismatch" }
        require(xs.size == widths.size) { "diffn: xs/widths size mismatch" }
        require(xs.size == heights.size) { "diffn: xs/heights size mismatch" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    /** Count of overlapping pairs under the current assignment. */
    private class State(var overlappingPairs: Int)

    private fun overlaps(
        x1: Int, y1: Int, w1: Int, h1: Int,
        x2: Int, y2: Int, w2: Int, h2: Int,
    ): Boolean {
        // Non-strict: zero w/h ⇒ degenerate; never overlaps.
        if (nonStrict && (w1 == 0 || h1 == 0 || w2 == 0 || h2 == 0)) return false
        val xOverlap = !(x1 + w1 <= x2 || x2 + w2 <= x1)
        val yOverlap = !(y1 + h1 <= y2 || y2 + h2 <= y1)
        return xOverlap && yOverlap
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var bad = 0
        for (i in xs.indices) {
            for (j in i + 1 until xs.size) {
                if (overlaps(
                        state.assignment.intValue(xs[i]), state.assignment.intValue(ys[i]),
                        widths[i], heights[i],
                        state.assignment.intValue(xs[j]), state.assignment.intValue(ys[j]),
                        widths[j], heights[j])
                ) bad++
            }
        }
        state.refPayload[factorId] = State(bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.overlappingPairs > 0
    }

    /** Brute-force delta: simulate the move, recount overlaps. O(n²). For typical diffn
     *  problem sizes (n ≤ ~50) this is acceptable; an incremental per-rect Δ is a clear
     *  follow-up once profiling indicates it. */
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = s.overlappingPairs > 0
        val newOverlaps = countOverlapsWithOverride(state, intVar, newValue)
        val willViolate = newOverlaps > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun countOverlapsWithOverride(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var bad = 0
        for (i in xs.indices) {
            val xi = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            val yi = if (ys[i] == intVar) newValue else state.assignment.intValue(ys[i])
            for (j in i + 1 until xs.size) {
                val xj = if (xs[j] == intVar) newValue else state.assignment.intValue(xs[j])
                val yj = if (ys[j] == intVar) newValue else state.assignment.intValue(ys[j])
                if (overlaps(xi, yi, widths[i], heights[i], xj, yj, widths[j], heights[j])) bad++
            }
        }
        return bad
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.overlappingPairs > 0
        // Recompute from scratch — payload is single int.
        s.overlappingPairs = countOverlapsAtCurrent(state)
        val nowViolated = s.overlappingPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun countOverlapsAtCurrent(state: LocalSearchState): Int {
        var bad = 0
        for (i in xs.indices) {
            for (j in i + 1 until xs.size) {
                if (overlaps(
                        state.assignment.intValue(xs[i]), state.assignment.intValue(ys[i]),
                        widths[i], heights[i],
                        state.assignment.intValue(xs[j]), state.assignment.intValue(ys[j]),
                        widths[j], heights[j])
                ) bad++
            }
        }
        return bad
    }

    /**
     * Singleton-violation check: when both rectangles' positions are singleton, verify
     * non-overlap directly. Per-axis bound propagation between non-singleton rectangles
     * lands with full strength later.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (i in xs.indices) {
            val dxi = state.intDomains[xs[i]]
            val dyi = state.intDomains[ys[i]]
            if (dxi.min != dxi.max || dyi.min != dyi.max) continue
            for (j in i + 1 until xs.size) {
                val dxj = state.intDomains[xs[j]]
                val dyj = state.intDomains[ys[j]]
                if (dxj.min != dxj.max || dyj.min != dyj.max) continue
                if (overlaps(
                        dxi.min, dyi.min, widths[i], heights[i],
                        dxj.min, dyj.min, widths[j], heights[j])
                ) return false
            }
        }
        return true
    }
}
