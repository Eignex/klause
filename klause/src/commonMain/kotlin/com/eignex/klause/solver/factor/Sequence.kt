package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `sequence(low, high, k, xs, S)` — for every length-[k] window
 * `xs[i..i+k-1]`, the number of `xs[j] ∈ S` lies in `[low, high]`. Used in rostering /
 * timetabling for sliding-window quotas ("at most 3 night shifts in any 7-day window").
 *
 * Decomposes structurally as `|xs| - k + 1` per-window cardinality constraints. The factor
 * keeps that structure explicit so a stronger native sequence propagator (regin-style flow
 * across overlapping windows) can replace the per-window bound check later.
 */
class Sequence(
    /** Inclusive lower bound on the in-window count. */
    val low: Int,
    /** Inclusive upper bound on the in-window count. */
    val high: Int,
    /** Sliding-window width. */
    val k: Int,
    /** The sequence variable ids. */
    val xs: IntArray,
    values: IntArray,
) : LocalSearchFactor {

    /** The distinct values, sorted ascending. */
    val values: IntArray = values.distinct().sorted().toIntArray()

    init {
        require(k >= 1) { "sequence: k must be ≥ 1" }
        require(k <= xs.size) { "sequence: k (=$k) must be ≤ |xs| (=${xs.size})" }
        require(low in 0..k) { "sequence: low (=$low) must be in 0..k" }
        require(high in low..k) { "sequence: high (=$high) must be in low..k" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    private fun matches(value: Int): Boolean {
        var lo = 0
        var hi = values.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mv = values[mid]
            when {
                mv < value -> lo = mid + 1
                mv > value -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    /** Per-window count of in-S elements. */
    private class State(val windowCounts: IntArray)

    private val windowCount: Int = xs.size - k + 1

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(windowCount)
        for (w in 0 until windowCount) {
            var c = 0
            for (j in 0 until k) if (matches(state.assignment.intValue(xs[w + j]))) c++
            counts[w] = c
        }
        state.refPayload[factorId] = State(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        for (c in s.windowCounts) if (c < low || c > high) return true
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolated(state, factorId)
        // Simulate per-window counts on a copy.
        val sim = s.windowCounts.copyOf()
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            val wasMatch = matches(state.assignment.intValue(intVar))
            val willMatch = matches(newValue)
            val delta = (if (willMatch) 1 else 0) - (if (wasMatch) 1 else 0)
            if (delta == 0) continue
            // Index i is in windows [max(0, i-k+1) .. min(windowCount-1, i)].
            val wLo = maxOf(0, i - k + 1)
            val wHi = minOf(windowCount - 1, i)
            for (w in wLo..wHi) sim[w] += delta
        }
        var willViolate = false
        for (c in sim) {
            if (c < low || c > high) {
                willViolate = true
                break
            }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = run {
            // Need pre-update violation. Compute by reversing the change on a copy.
            val sim = s.windowCounts.copyOf()
            for (i in xs.indices) {
                if (xs[i] != intVar) continue
                val wasMatch = matches(oldValue)
                val nowMatch = matches(cur)
                val delta = (if (wasMatch) 1 else 0) - (if (nowMatch) 1 else 0)
                if (delta == 0) continue
                val wLo = maxOf(0, i - k + 1)
                val wHi = minOf(windowCount - 1, i)
                for (w in wLo..wHi) sim[w] += delta
            }
            var v = false
            for (c in sim) {
                if (c < low || c > high) {
                    v = true
                    break
                }
            }
            v
        }
        // Apply real update.
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            val wasMatch = matches(oldValue)
            val nowMatch = matches(cur)
            val delta = (if (nowMatch) 1 else 0) - (if (wasMatch) 1 else 0)
            if (delta == 0) continue
            val wLo = maxOf(0, i - k + 1)
            val wHi = minOf(windowCount - 1, i)
            for (w in wLo..wHi) s.windowCounts[w] += delta
        }
        val nowViolated = isViolated(state, factorId)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Per-window cardinality bound check: each window's possible count is the number of
     * window-members whose domain still contains a value in S; definite count is the
     * number of singletons whose value is in S. If definite > high or possible < low for
     * any window, fail.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (w in 0 until windowCount) {
            var definite = 0
            var possible = 0
            for (j in 0 until k) {
                val d = state.intDomains[xs[w + j]]
                var allIn = true
                var anyIn = false
                d.forEach { value ->
                    if (matches(value)) {
                        anyIn = true
                    } else {
                        allIn = false
                    }
                }
                if (allIn) definite++
                if (anyIn) possible++
            }
            if (definite > high) return false
            if (possible < low) return false
        }
        return true
    }

    /** For each violated window, propose flips of in-window xs[j] that move the window
     *  count toward `[low, high]`. Below-low windows want a non-match → match flip;
     *  above-high windows want a match → non-match flip. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        for (w in 0 until windowCount) {
            val c = s.windowCounts[w]
            if (c in low..high) continue
            val needIncrease = c < low
            for (j in 0 until k) {
                val xi = xs[w + j]
                val d = state.problem.intDomains[xi]
                val cur = state.assignment.intValue(xi)
                val isMatch = matches(cur)
                if (isMatch && !needIncrease) {
                    // Pick any in-domain non-matching value.
                    var pick: Int? = null
                    d.forEach { if (pick == null && it != cur && !matches(it)) pick = it }
                    if (pick != null) sink.addChannelingIntSet(state, xi, pick!!)
                } else if (!isMatch && needIncrease) {
                    // Pick a matching value from the set.
                    var pick: Int? = null
                    for (vv in values) {
                        if (vv in d && vv != cur) {
                            pick = vv
                            break
                        }
                    }
                    if (pick != null) sink.addChannelingIntSet(state, xi, pick!!)
                }
            }
        }
    }
}
