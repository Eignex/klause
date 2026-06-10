package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

/**
 * `sort(xs, ys)` — [ys] is the non-decreasing sorted permutation of [xs] (same multiset
 * of values). Two constraints together: pairwise `ys[i] ≤ ys[i+1]` AND the multisets of
 * `xs` and `ys` are equal.
 *
 * Propagation: chain bound-tightening on `ys` (non-decreasing) and matching bounds
 * between `ys[0]` ↔ `min(xs)` / `ys[n-1]` ↔ `max(xs)`.
 */
class Sort(val xs: IntArray, val ys: IntArray) : Factor {

    init {
        require(xs.size == ys.size) { "sort: xs/ys size mismatch" }
        require(xs.isNotEmpty()) { "sort: empty arrays" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = mismatches(state, ov = -1, nv = 0) > 0

    private fun IntArray.toSortedArray(): IntArray = copyOf().also { it.sort() }

    /** Number of positions where `ys` differs from `sorted(xs)`, under an optional single-var
     *  override (`ov` = var id to substitute, or -1 for none). Counts both the order and the
     *  multiset discrepancy, so it shrinks monotonically as the search aligns the two. */
    private fun mismatches(state: LocalSearchState, ov: Int, nv: Int): Int {
        val xsVals = IntArray(
            xs.size,
        ) { i -> if (xs[i] == ov) nv else state.assignment.intValue(xs[i]) }.toSortedArray()
        val ysVals = IntArray(ys.size) { i -> if (ys[i] == ov) nv else state.assignment.intValue(ys[i]) }
        var m = 0
        for (i in ysVals.indices) if (ysVals[i] != xsVals[i]) m++
        return m
    }

    /** Graded violation: count of sorted-position mismatches, compressed. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(mismatches(state, ov = -1, nv = 0).toLong())

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val before = mismatches(state, ov = -1, nv = 0)
        val after = mismatches(state, ov = intVar, nv = newValue)
        return compressViolation(after.toLong()) - compressViolation(before.toLong())
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair by snapping `ys` to `sorted(xs)` at every position where they disagree, plus
     *  the symmetric xs-side: for each value v over-represented in `xs` relative to `ys`,
     *  propose retargeting some `xs[k] = v` to a value `v'` that `ys` has more of. Without
     *  the xs-side proposal, the LS engine can't reach feasibility when the *multiset* of
     *  `xs` needs to change to match `ys` — only its order. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val xsVals = IntArray(xs.size) { state.assignment.intValue(xs[it]) }
        val ysVals = IntArray(ys.size) { state.assignment.intValue(ys[it]) }
        val sortedXs = xsVals.copyOf().also { it.sort() }
        for (i in ys.indices) {
            val target = sortedXs[i]
            if (target != ysVals[i] && target in state.problem.intDomains[ys[i]]) {
                sink.addChannelingIntSet(state, ys[i], target)
            }
        }
        val xsCount = MutableIntIntMap().also { for (v in xsVals) it.addTo(v, 1) }
        val ysCount = MutableIntIntMap().also { for (v in ysVals) it.addTo(v, 1) }
        val over = IntArrayList()
        val under = IntArrayList()
        xsCount.forEach { v, c -> if (c > ysCount.getOrDefault(v, 0)) over.add(v) }
        ysCount.forEach { v, c -> if (c > xsCount.getOrDefault(v, 0)) under.add(v) }
        for (oi in 0 until over.size) {
            val v = over[oi]
            for (ui in 0 until under.size) {
                val vPrime = under[ui]
                for (k in xs.indices) {
                    if (xsVals[k] == v && vPrime in state.problem.intDomains[xs[k]]) {
                        sink.addChannelingIntSet(state, xs[k], vPrime)
                        break
                    }
                }
            }
        }
    }

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // All-singleton sanity: when every operand is pinned, ys must equal sorted xs.
        var allSingleton = true
        for (v in intVars) {
            if (state.intDomains[v].min != state.intDomains[v].max) {
                allSingleton = false
                break
            }
        }
        if (allSingleton) {
            val xv = IntArray(xs.size) { state.intDomains[xs[it]].min }.also { it.sort() }
            for (i in ys.indices) {
                if (state.intDomains[ys[i]].min != xv[i]) return false
            }
        }
        val antYs = state.composeIntVarAtomAntecedents(ys)
        val antXs = state.composeIntVarAtomAntecedents(xs)
        // ys non-decreasing.
        for (i in 0 until ys.size - 1) {
            val lo = state.intDomains[ys[i]].min
            if (!state.tightenIntMin(ys[i + 1], lo, antYs)) return false
        }
        for (i in ys.size - 2 downTo 0) {
            val hi = state.intDomains[ys[i + 1]].max
            if (!state.tightenIntMax(ys[i], hi, antYs)) return false
        }
        var xsMinOfMins = Int.MAX_VALUE
        var xsMinOfMaxes = Int.MAX_VALUE
        var xsMaxOfMins = Int.MIN_VALUE
        var xsMaxOfMaxes = Int.MIN_VALUE
        for (x in xs) {
            val d = state.intDomains[x]
            if (d.min < xsMinOfMins) xsMinOfMins = d.min
            if (d.max < xsMinOfMaxes) xsMinOfMaxes = d.max
            if (d.min > xsMaxOfMins) xsMaxOfMins = d.min
            if (d.max > xsMaxOfMaxes) xsMaxOfMaxes = d.max
        }
        if (!state.tightenIntMin(ys[0], xsMinOfMins, antXs)) return false
        if (!state.tightenIntMax(ys[0], xsMinOfMaxes, antXs)) return false
        val n = ys.size
        if (!state.tightenIntMin(ys[n - 1], xsMaxOfMins, antXs)) return false
        if (!state.tightenIntMax(ys[n - 1], xsMaxOfMaxes, antXs)) return false
        val yLo = state.intDomains[ys[0]].min
        val yHi = state.intDomains[ys[n - 1]].max
        for (x in xs) {
            if (!state.tightenIntMin(x, yLo, antYs)) return false
            if (!state.tightenIntMax(x, yHi, antYs)) return false
        }
        return true
    }
}
