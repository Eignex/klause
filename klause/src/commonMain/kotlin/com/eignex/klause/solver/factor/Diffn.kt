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
 * Widths and heights are constants; positions are variables.
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
     *  problem sizes (n ≤ ~50) this is acceptable. */
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
     * Pairwise compulsory-parts / disjunctive propagation. For each pair (i, j):
     *   - **Must-overlap on an axis** iff the compulsory zone of one rect contains a
     *     cell shared with the other — equivalently, the latest start of one rect
     *     is ≤ the earliest end of the other (minus 1) on both sides. When both
     *     axes must overlap, the pair is infeasible.
     *   - **Must-overlap on x, can-vary on y** ⇒ enforce y-disjointness via the
     *     disjunctive `(yᵢ + hᵢ ≤ yⱼ) ∨ (yⱼ + hⱼ ≤ yᵢ)`. When only one disjunct is
     *     reachable under current bounds, tighten the corresponding `y.max` / `y.min`.
     *   - Symmetric for must-overlap on y.
     *
     * Iterated to fixed point implicitly via the engine's propagator loop. The
     * "compulsory parts" propagator subsumes singleton conflict and prunes non-singleton
     * rectangles whose forced positions force a non-overlap direction on the orthogonal
     * axis.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        for (i in 0 until n) {
            val wI = widths[i]; val hI = heights[i]
            if (nonStrict && (wI == 0 || hI == 0)) continue
            val xiLo = state.intDomains[xs[i]].min
            val xiHi = state.intDomains[xs[i]].max
            val yiLo = state.intDomains[ys[i]].min
            val yiHi = state.intDomains[ys[i]].max
            for (j in i + 1 until n) {
                val wJ = widths[j]; val hJ = heights[j]
                if (nonStrict && (wJ == 0 || hJ == 0)) continue
                val xjLo = state.intDomains[xs[j]].min
                val xjHi = state.intDomains[xs[j]].max
                val yjLo = state.intDomains[ys[j]].min
                val yjHi = state.intDomains[ys[j]].max
                // Must-overlap on x iff every (xi, xj) in their domains has i and j sharing
                // an x-cell. Equivalent: xiHi < xjLo + wJ AND xjHi < xiLo + wI.
                val xMust = xiHi < xjLo + wJ && xjHi < xiLo + wI
                val yMust = yiHi < yjLo + hJ && yjHi < yiLo + hI
                if (xMust && yMust) return false
                if (xMust) {
                    // Must enforce y-disjointness. Option A: y_i + h_i ≤ y_j. Option B: y_j + h_j ≤ y_i.
                    val aFeasible = yiLo + hI <= yjHi
                    val bFeasible = yjLo + hJ <= yiHi
                    if (!aFeasible && !bFeasible) return false
                    if (aFeasible && !bFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(ys[i], yjHi - hI, ant)) return false
                        if (!state.tightenIntMin(ys[j], yiLo + hI, ant)) return false
                    } else if (bFeasible && !aFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(ys[j], yiHi - hJ, ant)) return false
                        if (!state.tightenIntMin(ys[i], yjLo + hJ, ant)) return false
                    }
                } else if (yMust) {
                    val aFeasible = xiLo + wI <= xjHi
                    val bFeasible = xjLo + wJ <= xiHi
                    if (!aFeasible && !bFeasible) return false
                    if (aFeasible && !bFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(xs[i], xjHi - wI, ant)) return false
                        if (!state.tightenIntMin(xs[j], xiLo + wI, ant)) return false
                    } else if (bFeasible && !aFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(xs[j], xiHi - wJ, ant)) return false
                        if (!state.tightenIntMin(xs[i], xjLo + wJ, ant)) return false
                    }
                }
            }
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    /** Repair: for each overlapping pair, propose moves that escape the overlap.
     *  Per-pair candidate set:
     *   - 4 single-axis shifts for rectangle i (existing).
     *   - 4 symmetric single-axis shifts for rectangle j.
     *   - 4 diagonal Compound shifts on i (left+down, left+up, right+down, right+up) —
     *     useful when single-axis shifts collide with adjacent rectangles.
     *   - 1 swap Compound that exchanges (xs[i], ys[i]) with (xs[j], ys[j]) — escapes
     *     deadlocks where i and j mutually block each other on both axes.
     */
    override fun proposeRepairMoves(
        state: LocalSearchState,
        factorId: Int,
        sink: com.eignex.klause.solver.localsearch.MoveSink,
    ) {
        if (!isViolated(state, factorId)) return
        val n = xs.size
        for (i in 0 until n) {
            val xi = state.assignment.intValue(xs[i])
            val yi = state.assignment.intValue(ys[i])
            for (j in i + 1 until n) {
                val xj = state.assignment.intValue(xs[j])
                val yj = state.assignment.intValue(ys[j])
                if (!overlaps(xi, yi, widths[i], heights[i], xj, yj, widths[j], heights[j])) continue
                val dxsI = state.problem.intDomains[xs[i]]
                val dysI = state.problem.intDomains[ys[i]]
                val dxsJ = state.problem.intDomains[xs[j]]
                val dysJ = state.problem.intDomains[ys[j]]
                // i-side single-axis shifts.
                val leftI = xj - widths[i]
                val rightI = xj + widths[j]
                val downI = yj - heights[i]
                val upI = yj + heights[j]
                if (leftI in dxsI && leftI != xi) sink.addChannelingIntSet(state, xs[i], leftI)
                if (rightI in dxsI && rightI != xi) sink.addChannelingIntSet(state, xs[i], rightI)
                if (downI in dysI && downI != yi) sink.addChannelingIntSet(state, ys[i], downI)
                if (upI in dysI && upI != yi) sink.addChannelingIntSet(state, ys[i], upI)
                // j-side symmetric shifts.
                val leftJ = xi - widths[j]
                val rightJ = xi + widths[i]
                val downJ = yi - heights[j]
                val upJ = yi + heights[i]
                if (leftJ in dxsJ && leftJ != xj) sink.addChannelingIntSet(state, xs[j], leftJ)
                if (rightJ in dxsJ && rightJ != xj) sink.addChannelingIntSet(state, xs[j], rightJ)
                if (downJ in dysJ && downJ != yj) sink.addChannelingIntSet(state, ys[j], downJ)
                if (upJ in dysJ && upJ != yj) sink.addChannelingIntSet(state, ys[j], upJ)
                // Diagonal 2-axis shifts on i. Useful when single-axis moves would collide
                // with a third rectangle but the diagonal corner is free.
                fun proposeDiagonal(nx: Int, ny: Int) {
                    if (nx == xi && ny == yi) return
                    if (nx !in dxsI || ny !in dysI) return
                    sink.addCompound(listOf(
                        com.eignex.klause.solver.Move.IntSet(xs[i], nx),
                        com.eignex.klause.solver.Move.IntSet(ys[i], ny),
                    ))
                }
                proposeDiagonal(leftI, downI)
                proposeDiagonal(leftI, upI)
                proposeDiagonal(rightI, downI)
                proposeDiagonal(rightI, upI)
                // Swap rectangles i and j (4-flip compound). Only when domains allow the
                // exchange and at least one axis actually shifts on each side — degenerate
                // axes (equal current values) would emit no-op IntSets that the engine
                // would reject anyway.
                if (xi != xj && yi != yj) {
                    if (xj in dxsI && yj in dysI && xi in dxsJ && yi in dysJ) {
                        sink.addCompound(listOf(
                            com.eignex.klause.solver.Move.IntSet(xs[i], xj),
                            com.eignex.klause.solver.Move.IntSet(ys[i], yj),
                            com.eignex.klause.solver.Move.IntSet(xs[j], xi),
                            com.eignex.klause.solver.Move.IntSet(ys[j], yi),
                        ))
                    }
                }
            }
        }
    }
}
