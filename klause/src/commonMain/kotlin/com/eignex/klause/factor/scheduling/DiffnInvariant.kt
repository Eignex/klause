package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.scheduling.internals.DiffnLsState
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink

/**
 * LS invariant for [Diffn]. Constructed by [Diffn.asInvariant] and maintains an
 * overlapping-pair count, computing deltas using the affected-pair trick.
 */
internal class DiffnInvariant(
    private val xs: IntArray,
    private val ys: IntArray,
    private val widths: LongArray,
    private val heights: LongArray,
    private val widthVars: IntArray?,
    private val heightVars: IntArray?,
    private val nonStrict: Boolean,
    private val n: Int,
    private val varToRectOf: (Int) -> Int,
) : Invariant {

    // Effective rectangle components applying an optional (overrideVar → overrideVal).
    private fun rx(s: LocalSearchState, i: Int, ov: Int, nv: Long): Long = if (xs[i] == ov) {
        nv
    } else {
        s.assignment.intValue(
            xs[i],
        )
    }

    private fun ry(s: LocalSearchState, i: Int, ov: Int, nv: Long): Long = if (ys[i] == ov) {
        nv
    } else {
        s.assignment.intValue(
            ys[i],
        )
    }

    private fun rw(s: LocalSearchState, i: Int, ov: Int, nv: Long): Long {
        val wv = widthVars ?: return widths[i]
        return if (wv[i] == ov) nv else s.assignment.intValue(wv[i])
    }

    private fun rh(s: LocalSearchState, i: Int, ov: Int, nv: Long): Long {
        val hv = heightVars ?: return heights[i]
        return if (hv[i] == ov) nv else s.assignment.intValue(hv[i])
    }

    private fun overlaps(x1: Long, y1: Long, w1: Long, h1: Long, x2: Long, y2: Long, w2: Long, h2: Long): Boolean {
        if (nonStrict && (w1 == 0L || h1 == 0L || w2 == 0L || h2 == 0L)) return false
        val xOverlap = !(x1 + w1 <= x2 || x2 + w2 <= x1)
        val yOverlap = !(y1 + h1 <= y2 || y2 + h2 <= y1)
        return xOverlap && yOverlap
    }

    /** Overlapping-pair count with an optional single-variable override (`ov < 0` = none). */
    private fun countOverlaps(state: LocalSearchState, ov: Int, nv: Long): Int {
        var bad = 0
        for (i in 0 until n) {
            val xi = rx(state, i, ov, nv)
            val yi = ry(state, i, ov, nv)
            val wi = rw(state, i, ov, nv)
            val hi = rh(state, i, ov, nv)
            for (j in i + 1 until n) {
                if (overlaps(
                        xi,
                        yi,
                        wi,
                        hi,
                        rx(state, j, ov, nv),
                        ry(state, j, ov, nv),
                        rw(state, j, ov, nv),
                        rh(state, j, ov, nv),
                    )
                ) {
                    bad++
                }
            }
        }
        return bad
    }

    /** Number of overlapping pairs that include rectangle [r], under an optional single-var
     *  override (`ov < 0` = none). */
    private fun pairsInvolvingRect(state: LocalSearchState, r: Int, ov: Int, nv: Long): Int {
        val xr = rx(state, r, ov, nv)
        val yr = ry(state, r, ov, nv)
        val wr = rw(state, r, ov, nv)
        val hr = rh(state, r, ov, nv)
        var bad = 0
        for (j in 0 until n) {
            if (j == r) continue
            if (overlaps(
                    xr,
                    yr,
                    wr,
                    hr,
                    rx(state, j, ov, nv),
                    ry(state, j, ov, nv),
                    rw(state, j, ov, nv),
                    rh(state, j, ov, nv),
                )
            ) {
                bad++
            }
        }
        return bad
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = DiffnLsState(countOverlaps(state, ov = -1, nv = 0L))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as DiffnLsState).overlappingPairs > 0

    /** Graded violation: the number of overlapping rectangle pairs, compressed — a move that
     *  separates some (but not all) overlaps scores a real improvement instead of 0. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = compressViolation(
        (state.refPayload[factorId] as DiffnLsState).overlappingPairs.toLong(),
        state.violationSoftCap,
    )

    /** Affected-pair delta: a single-var move (position OR size) only changes the overlap
     *  status of pairs that include the moved rectangle, so the new total is
     *  `overlappingPairs − oldPairsInvolving(r) + newPairsInvolving(r)` in O(n). The stored
     *  [DiffnLsState.overlappingPairs] is kept exact by [applyIntSet]'s full recount, so this fast
     *  path needs no drift correction. Falls back to the O(n²) recount when the moved var is
     *  shared across rectangles (`varToRectOf == -1`). */
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as DiffnLsState
        val r = varToRectOf(intVar)
        val after = if (r < 0) {
            countOverlaps(state, intVar, newValue)
        } else {
            val oldPairsR = pairsInvolvingRect(state, r, ov = -1, nv = 0L)
            val newPairsR = pairsInvolvingRect(state, r, ov = intVar, nv = newValue)
            s.overlappingPairs - oldPairsR + newPairsR
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(s.overlappingPairs.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as DiffnLsState
        if (state.assignment.intValue(intVar) == oldValue) return 0
        val before = s.overlappingPairs
        // Mirror the affected-pair delta: the move is already applied, so the after-pairs involving
        // the moved rectangle are read from the current assignment and the before-pairs by overriding
        // the moved variable back to oldValue. Only pairs touching that rectangle changed, so this is
        // O(n) — the full O(n^2) recount is kept only for a variable shared across rectangles.
        val r = varToRectOf(intVar)
        s.overlappingPairs = if (r < 0) {
            countOverlaps(state, ov = -1, nv = 0L)
        } else {
            val newPairsR = pairsInvolvingRect(state, r, ov = -1, nv = 0L)
            val oldPairsR = pairsInvolvingRect(state, r, ov = intVar, nv = oldValue)
            before - oldPairsR + newPairsR
        }
        return compressViolation(s.overlappingPairs.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /** Repair: for each overlapping pair, propose single-axis shifts of either rectangle out
     *  of the overlap, diagonal compound shifts, and a position swap. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (i in 0 until n) {
            val xi = rx(state, i, -1, 0L)
            val yi = ry(state, i, -1, 0L)
            val wi = rw(state, i, -1, 0L)
            val hi = rh(state, i, -1, 0L)
            for (j in i + 1 until n) {
                val xj = rx(state, j, -1, 0L)
                val yj = ry(state, j, -1, 0L)
                val wj = rw(state, j, -1, 0L)
                val hj = rh(state, j, -1, 0L)
                if (!overlaps(xi, yi, wi, hi, xj, yj, wj, hj)) continue
                val dxsI = state.rootDomains[xs[i]]
                val dysI = state.rootDomains[ys[i]]
                val dxsJ = state.rootDomains[xs[j]]
                val dysJ = state.rootDomains[ys[j]]
                val leftI = xj - wi
                val rightI = xj + wj
                val downI = yj - hi
                val upI = yj + hj
                if (leftI in dxsI && leftI != xi) sink.addChannelingIntSet(state, xs[i], leftI)
                if (rightI in dxsI && rightI != xi) sink.addChannelingIntSet(state, xs[i], rightI)
                if (downI in dysI && downI != yi) sink.addChannelingIntSet(state, ys[i], downI)
                if (upI in dysI && upI != yi) sink.addChannelingIntSet(state, ys[i], upI)
                val leftJ = xi - wj
                val rightJ = xi + wi
                val downJ = yi - hj
                val upJ = yi + hi
                if (leftJ in dxsJ && leftJ != xj) sink.addChannelingIntSet(state, xs[j], leftJ)
                if (rightJ in dxsJ && rightJ != xj) sink.addChannelingIntSet(state, xs[j], rightJ)
                if (downJ in dysJ && downJ != yj) sink.addChannelingIntSet(state, ys[j], downJ)
                if (upJ in dysJ && upJ != yj) sink.addChannelingIntSet(state, ys[j], upJ)
                fun proposeDiagonal(nx: Long, ny: Long) {
                    if (nx == xi && ny == yi) return
                    if (nx !in dxsI || ny !in dysI) return
                    sink.addCompound(
                        listOf(
                            IntSet(xs[i], nx),
                            IntSet(ys[i], ny),
                        ),
                    )
                }
                proposeDiagonal(leftI, downI)
                proposeDiagonal(leftI, upI)
                proposeDiagonal(rightI, downI)
                proposeDiagonal(rightI, upI)
                if (xi != xj && yi != yj &&
                    xj in dxsI && yj in dysI && xi in dxsJ && yi in dysJ
                ) {
                    sink.addCompound(
                        listOf(
                            IntSet(xs[i], xj),
                            IntSet(ys[i], yj),
                            IntSet(xs[j], xi),
                            IntSet(ys[j], yi),
                        ),
                    )
                }
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: swap the positions of two rectangles with identical
     *  current footprint (same width and height). Each rectangle takes the other's lower-left
     *  corner, so the set of occupied cells is exactly preserved and no overlap is introduced. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < DIFFN_STRUCTURED_SWAP_CAP &&
            attempts < DIFFN_STRUCTURED_SWAP_CAP * DIFFN_SWAP_ATTEMPT_STRIDE
        ) {
            attempts++
            val i = state.rng.nextInt(n)
            val j = state.rng.nextInt(n)
            if (i == j || xs[i] == ys[i] || xs[j] == ys[j]) continue
            if (rw(state, i, -1, 0L) != rw(state, j, -1, 0L)) continue
            if (rh(state, i, -1, 0L) != rh(state, j, -1, 0L)) continue
            val xi = state.assignment.intValue(xs[i])
            val yi = state.assignment.intValue(ys[i])
            val xj = state.assignment.intValue(xs[j])
            val yj = state.assignment.intValue(ys[j])
            if (xi == xj && yi == yj) continue
            if (xj !in state.rootDomains[xs[i]] || yj !in state.rootDomains[ys[i]]) continue
            if (xi !in state.rootDomains[xs[j]] || yi !in state.rootDomains[ys[j]]) continue
            val parts = ArrayList<Move>(4)
            if (xj != xi) parts.add(IntSet(xs[i], xj))
            if (yj != yi) parts.add(IntSet(ys[i], yj))
            if (xi != xj) parts.add(IntSet(xs[j], xi))
            if (yi != yj) parts.add(IntSet(ys[j], yi))
            sink.addCompound(parts)
            emitted++
        }
    }

    /** Feasible init: lay the rectangles out left-to-right so they are pairwise disjoint on the
     *  x-axis (hence non-overlapping for any y). Each `xs[i]` takes the first in-domain value at or
     *  after the previous rectangle's right edge; `ys[i]` takes its domain minimum. Returns false —
     *  leaving the random assignment — when a rectangle can't be placed in domain. */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (n == 0) return false
        var prevRight = Long.MIN_VALUE
        val order = (0 until n).sortedBy { state.rootDomains[xs[it]].min }
        for (i in order) {
            val xv = xs[i]
            val w = rw(state, i, -1, 0L)
            if (!state.assumptions.isFrozenInt(xv)) {
                val d = state.rootDomains[xv]
                val cand = if (prevRight > d.min) prevRight else d.min
                if (cand > d.max) return false
                var s = Long.MIN_VALUE
                var found = false
                d.forEach {
                    if (!found && it >= cand) {
                        s = it
                        found = true
                    }
                }
                if (!found) return false
                state.assignment.setInt(xv, s)
                prevRight = s + w
            } else {
                val s = state.assignment.intValue(xv)
                if (s < prevRight) return false
                prevRight = s + w
            }
            val yv = ys[i]
            if (!state.assumptions.isFrozenInt(yv)) state.assignment.setInt(yv, state.rootDomains[yv].min)
        }
        return true
    }
}

private const val DIFFN_STRUCTURED_SWAP_CAP: Int = 4
private const val DIFFN_SWAP_ATTEMPT_STRIDE: Int = 8
