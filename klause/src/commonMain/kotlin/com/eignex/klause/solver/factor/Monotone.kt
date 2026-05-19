package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `xs[i] (<= or <) xs[i+1]` for every adjacent pair. Covers all four MiniZinc variants:
 *
 *  - `increasing_int(xs)`         — non-strict ascending: `xs[i] <= xs[i+1]`
 *  - `decreasing_int(xs)`         — non-strict descending: `xs[i] >= xs[i+1]`
 *  - `strictly_increasing_int(xs)` — strict ascending: `xs[i] < xs[i+1]`
 *  - `strictly_decreasing_int(xs)` — strict descending: `xs[i] > xs[i+1]`
 *
 * Single class with [direction] and [strict] flags so MiniZinc's four predicate variants
 * all route to one factor. Propagation in this first cut is the default no-op — chained
 * bound-tightening lands when full propagator strength is in scope (next step). LS side
 * counts pairwise violations and proposes nudge moves on the offending vars.
 */
class Monotone(
    val xs: IntArray,
    val direction: Direction,
    val strict: Boolean,
) : LocalSearchFactor {

    enum class Direction { Increasing, Decreasing }

    init {
        require(xs.size >= 2) { "Monotone needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Per-factor payload: count of adjacent pairs currently in violation. Maintained
     *  incrementally via [applyIntSet]; queried by [isViolated] as `> 0`. */
    private class State(var violatedPairs: Int)

    /** Returns `true` if the pair `(a, b)` is *in order* under this factor's semantics. */
    private fun ordered(a: Int, b: Int): Boolean = when (direction) {
        Direction.Increasing -> if (strict) a < b else a <= b
        Direction.Decreasing -> if (strict) a > b else a >= b
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var bad = 0
        for (i in 0 until xs.size - 1) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(xs[i + 1])
            if (!ordered(a, b)) bad++
        }
        state.refPayload[factorId] = State(bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violatedPairs > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val (deltaPairs) = countDeltaPairs(state, intVar, newValue)
        val wasViolated = s.violatedPairs > 0
        val willViolate = (s.violatedPairs + deltaPairs) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.violatedPairs > 0
        // Recompute the delta by simulating the inverse change (the assignment has already
        // been updated to `cur`; we score the pairs around intVar against (oldValue → cur)).
        val (deltaPairs) = countDeltaPairs(state, intVar, cur, simulatedFromValue = oldValue)
        s.violatedPairs += deltaPairs
        val nowViolated = s.violatedPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * For a candidate change `intVar : oldVal → newVal`, return the net change in number of
     * currently-violated adjacent pairs that include [intVar]. Visits the (≤ 2) adjacent
     * positions of every occurrence of [intVar] in [xs].
     *
     * If [simulatedFromValue] is `null`, "old" is read from the current assignment (used
     * by [deltaIfIntSet]); otherwise it's the supplied value (used by [applyIntSet]
     * where the assignment is already post-move).
     */
    private fun countDeltaPairs(
        state: LocalSearchState,
        intVar: Int,
        newValue: Int,
        simulatedFromValue: Int? = null,
    ): IntArray {
        // The signature returns IntArray of length 1 so callers can destructure cleanly;
        // could be `Int` but Kotlin destructuring of (Int) is awkward.
        var delta = 0
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            // Left neighbour pair (i-1, i).
            if (i > 0) {
                val left = state.assignment.intValue(xs[i - 1])
                val oldVal = simulatedFromValue ?: state.assignment.intValue(intVar)
                val wasOK = ordered(left, oldVal)
                val willOK = ordered(left, newValue)
                if (wasOK && !willOK) delta++
                if (!wasOK && willOK) delta--
            }
            // Right neighbour pair (i, i+1).
            if (i < xs.size - 1) {
                val right = state.assignment.intValue(xs[i + 1])
                val oldVal = simulatedFromValue ?: state.assignment.intValue(intVar)
                val wasOK = ordered(oldVal, right)
                val willOK = ordered(newValue, right)
                if (wasOK && !willOK) delta++
                if (!wasOK && willOK) delta--
            }
        }
        return intArrayOf(delta)
    }

    /**
     * Chain pairwise bound tightening: for non-strict variants, every prefix's min bounds
     * the next index's min (ascending) / max (descending); similarly for suffixes. Strict
     * variants tighten by ±1. A single forward + backward sweep reaches fixpoint over the
     * chain in O(n) when domains are intervals; for sparse domains the same calls preserve
     * holes via the engine's `withMinAtLeast` / `withMaxAtMost`.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val bump = if (strict) 1 else 0
        when (direction) {
            Direction.Increasing -> {
                // Forward: xs[i+1].min >= xs[i].min + bump.
                for (i in 0 until xs.size - 1) {
                    val lo = state.intDomains[xs[i]].min + bump
                    if (!state.tightenIntMin(xs[i + 1], lo)) return false
                }
                // Backward: xs[i].max <= xs[i+1].max - bump.
                for (i in xs.size - 2 downTo 0) {
                    val hi = state.intDomains[xs[i + 1]].max - bump
                    if (!state.tightenIntMax(xs[i], hi)) return false
                }
            }
            Direction.Decreasing -> {
                for (i in 0 until xs.size - 1) {
                    val hi = state.intDomains[xs[i]].max - bump
                    if (!state.tightenIntMax(xs[i + 1], hi)) return false
                }
                for (i in xs.size - 2 downTo 0) {
                    val lo = state.intDomains[xs[i + 1]].min + bump
                    if (!state.tightenIntMin(xs[i], lo)) return false
                }
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // Find one violated pair; propose nudging either side toward the in-order side.
        for (i in 0 until xs.size - 1) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(xs[i + 1])
            if (ordered(a, b)) continue
            // Out-of-order; nudge each side toward an in-order target.
            val da = state.problem.intDomains[xs[i]]
            val db = state.problem.intDomains[xs[i + 1]]
            when (direction) {
                Direction.Increasing -> {
                    val targetLeft = if (strict) b - 1 else b
                    if (targetLeft in da) sink.addIntSet(xs[i], targetLeft)
                    val targetRight = if (strict) a + 1 else a
                    if (targetRight in db) sink.addIntSet(xs[i + 1], targetRight)
                }
                Direction.Decreasing -> {
                    val targetLeft = if (strict) b + 1 else b
                    if (targetLeft in da) sink.addIntSet(xs[i], targetLeft)
                    val targetRight = if (strict) a - 1 else a
                    if (targetRight in db) sink.addIntSet(xs[i + 1], targetRight)
                }
            }
            return
        }
    }
}
