package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `diffn(xs, ys, widths, heights)` — pairwise non-overlapping 2D rectangles. Each rectangle
 * `i` has lower-left corner `(xs(i), ys(i))` and dimensions `(widths(i), heights(i))`.
 * Two rectangles non-overlap iff they're disjoint on at least one axis.
 *
 * Sizes may be **constant or variable**. When [widthVars] / [heightVars] are non-null the
 * dimensions are themselves variables (read from the assignment, and part of [intVars] so the
 * search may resize as well as move); otherwise the constant [widths] / [heights] are used.
 * The two axes are independent — e.g. constant widths with variable heights is allowed.
 *
 * [nonStrict] = true treats zero-width / zero-height rectangles as never overlapping
 * (the `diffn_nonstrict` variant). With [nonStrict] = false (the default), even zero-
 * dimensional rectangles must satisfy the non-overlap criterion.
 */
class Diffn(
    /** Variable ids the constraint ranges over. */
    val xs: IntArray,
    /** Second-vector variable ids. */
    val ys: IntArray,
    val widths: IntArray,
    val heights: IntArray,
    val widthVars: IntArray? = null,
    val heightVars: IntArray? = null,
    val nonStrict: Boolean = false,
) : Factor {

    private val n: Int = xs.size

    init {
        require(xs.size == ys.size) { "diffn: xs/ys size mismatch" }
        require(widthVars != null || xs.size == widths.size) { "diffn: xs/widths size mismatch" }
        require(heightVars != null || xs.size == heights.size) { "diffn: xs/heights size mismatch" }
        require(widthVars == null || widthVars.size == n) { "diffn: widthVars size mismatch" }
        require(heightVars == null || heightVars.size == n) { "diffn: heightVars size mismatch" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Diffn(
        xs.remapVars(intMap),
        ys.remapVars(intMap),
        widths,
        heights,
        widthVars?.remapVars(intMap),
        heightVars?.remapVars(intMap),
        nonStrict,
    )

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        xs + ys + (widthVars ?: EmptyIntArray) + (heightVars ?: EmptyIntArray)

    /** Whether the search can resize rectangles (var dimensions present). */
    private val varSize: Boolean = widthVars != null || heightVars != null

    /** var id → the single rectangle index it belongs to, or `-1` when the same id is shared
     *  by ≥2 distinct rectangles (a degenerate model). The affected-pair delta only touches
     *  the moved rectangle, which is exact iff the moved var maps to exactly one rectangle;
     *  a shared id forces the O(n²) full-recount fallback to preserve exact semantics. */
    private val varToRect: Map<Int, Int> = run {
        val m = HashMap<Int, Int>(n * 2)
        fun record(v: Int, i: Int) {
            val prev = m[v]
            m[v] = if (prev == null || prev == i) i else -1
        }
        for (i in 0 until n) {
            record(xs[i], i)
            record(ys[i], i)
            widthVars?.let { record(it[i], i) }
            heightVars?.let { record(it[i], i) }
        }
        m
    }

    /** Number of overlapping pairs that include rectangle [r], under an optional single-var
     *  override (`ov < 0` = none). The override var belongs to [r], so only [r]'s own
     *  components change; every other rectangle reads its current assignment. O(n). */
    private fun pairsInvolvingRect(state: LocalSearchState, r: Int, ov: Int, nv: Int): Int {
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

    /** Count of overlapping pairs under the current assignment. */
    private class State(var overlappingPairs: Int)

    // Effective rectangle components, applying an optional (overrideVar → overrideVal). Sizes
    // come from the size-vars when present, else the constants. `ov < 0` means no override.
    private fun rx(s: LocalSearchState, i: Int, ov: Int, nv: Int): Int =
        if (xs[i] == ov) nv else s.assignment.intValue(xs[i])
    private fun ry(s: LocalSearchState, i: Int, ov: Int, nv: Int): Int =
        if (ys[i] == ov) nv else s.assignment.intValue(ys[i])
    private fun rw(s: LocalSearchState, i: Int, ov: Int, nv: Int): Int = if (widthVars == null) {
        widths[i]
    } else if (widthVars[i] == ov) {
        nv
    } else {
        s.assignment.intValue(widthVars[i])
    }
    private fun rh(s: LocalSearchState, i: Int, ov: Int, nv: Int): Int = if (heightVars == null) {
        heights[i]
    } else if (heightVars[i] == ov) {
        nv
    } else {
        s.assignment.intValue(heightVars[i])
    }

    private fun overlaps(x1: Int, y1: Int, w1: Int, h1: Int, x2: Int, y2: Int, w2: Int, h2: Int): Boolean {
        if (nonStrict && (w1 == 0 || h1 == 0 || w2 == 0 || h2 == 0)) return false
        val xOverlap = !(x1 + w1 <= x2 || x2 + w2 <= x1)
        val yOverlap = !(y1 + h1 <= y2 || y2 + h2 <= y1)
        return xOverlap && yOverlap
    }

    /** Overlapping-pair count with an optional single-variable override (`ov < 0` = none). */
    private fun countOverlaps(state: LocalSearchState, ov: Int, nv: Int): Int {
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

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = State(countOverlaps(state, ov = -1, nv = 0))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as State).overlappingPairs > 0

    /** Graded violation: the number of overlapping rectangle pairs, compressed — a move that
     *  separates some (but not all) overlaps scores a real improvement instead of 0. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as State).overlappingPairs.toLong(), state.violationSoftCap)

    /** Affected-pair delta: a single-var move (position OR size) only changes the overlap
     *  status of pairs that include the moved rectangle, so the new total is
     *  `overlappingPairs − oldPairsInvolving(r) + newPairsInvolving(r)` in O(n). The stored
     *  [State.overlappingPairs] is kept exact by [applyIntSet]'s full recount, so this fast
     *  path needs no drift correction. Falls back to the O(n²) recount when the moved var is
     *  shared across rectangles (`varToRect == -1`). */
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val r = varToRect[intVar]
        val after = if (r == null || r < 0) {
            countOverlaps(state, intVar, newValue)
        } else {
            val oldPairsR = pairsInvolvingRect(state, r, ov = -1, nv = 0)
            val newPairsR = pairsInvolvingRect(state, r, ov = intVar, nv = newValue)
            s.overlappingPairs - oldPairsR + newPairsR
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(s.overlappingPairs.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        if (state.assignment.intValue(intVar) == oldValue) return 0
        val before = s.overlappingPairs
        s.overlappingPairs = countOverlaps(state, ov = -1, nv = 0)
        return compressViolation(s.overlappingPairs.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /**
     * Pairwise compulsory-parts / disjunctive propagation (constant-size only). When any
     * dimension is variable the size-dependent bound reasoning no longer holds, so we fall
     * back to the sound check: with the *minimum* possible sizes, if a pair must still overlap
     * on both axes the constraint is infeasible; otherwise no pruning. This keeps propagation
     * sound (never removes a feasible value) while LS does the heavy lifting on var-size diffn.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (varSize) return propagateVarSizeSoundOnly(state)
        val n = xs.size
        for (i in 0 until n) {
            val wI = widths[i]
            val hI = heights[i]
            if (nonStrict && (wI == 0 || hI == 0)) continue
            val xiLo = state.intDomains[xs[i]].min
            val xiHi = state.intDomains[xs[i]].max
            val yiLo = state.intDomains[ys[i]].min
            val yiHi = state.intDomains[ys[i]].max
            for (j in i + 1 until n) {
                val wJ = widths[j]
                val hJ = heights[j]
                if (nonStrict && (wJ == 0 || hJ == 0)) continue
                val xjLo = state.intDomains[xs[j]].min
                val xjHi = state.intDomains[xs[j]].max
                val yjLo = state.intDomains[ys[j]].min
                val yjHi = state.intDomains[ys[j]].max
                val xMust = xiHi < xjLo + wJ && xjHi < xiLo + wI
                val yMust = yiHi < yjLo + hJ && yjHi < yiLo + hI
                if (xMust && yMust) return false
                if (xMust) {
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

    /** Sound-only infeasibility check for the variable-size case: a pair is unconditionally
     *  infeasible iff it must overlap on both axes even at the *smallest* sizes each var allows. */
    private fun propagateVarSizeSoundOnly(state: PropagationState): Boolean {
        fun wMin(i: Int) = if (widthVars == null) widths[i] else state.intDomains[widthVars[i]].min
        fun hMin(i: Int) = if (heightVars == null) heights[i] else state.intDomains[heightVars[i]].min
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val wI = wMin(i)
                val hI = hMin(i)
                val wJ = wMin(j)
                val hJ = hMin(j)
                if (nonStrict && (wI == 0 || hI == 0 || wJ == 0 || hJ == 0)) continue
                val xMust = state.intDomains[xs[i]].max < state.intDomains[xs[j]].min + wJ &&
                    state.intDomains[xs[j]].max < state.intDomains[xs[i]].min + wI
                val yMust = state.intDomains[ys[i]].max < state.intDomains[ys[j]].min + hJ &&
                    state.intDomains[ys[j]].max < state.intDomains[ys[i]].min + hI
                if (xMust && yMust) return false
            }
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    /** Repair: for each overlapping pair, propose single-axis shifts of either rectangle out
     *  of the overlap, diagonal compound shifts, and a position swap. Sizes are read at their
     *  current values (so the escape distances respect variable dimensions). */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (i in 0 until n) {
            val xi = rx(state, i, -1, 0)
            val yi = ry(state, i, -1, 0)
            val wi = rw(state, i, -1, 0)
            val hi = rh(state, i, -1, 0)
            for (j in i + 1 until n) {
                val xj = rx(state, j, -1, 0)
                val yj = ry(state, j, -1, 0)
                val wj = rw(state, j, -1, 0)
                val hj = rh(state, j, -1, 0)
                if (!overlaps(xi, yi, wi, hi, xj, yj, wj, hj)) continue
                val dxsI = state.problem.intDomains[xs[i]]
                val dysI = state.problem.intDomains[ys[i]]
                val dxsJ = state.problem.intDomains[xs[j]]
                val dysJ = state.problem.intDomains[ys[j]]
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
                fun proposeDiagonal(nx: Int, ny: Int) {
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
}
