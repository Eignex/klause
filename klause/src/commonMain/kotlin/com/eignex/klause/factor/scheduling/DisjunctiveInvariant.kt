package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.scheduling.internals.firstInDomainAtLeast
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.argsortBy
import kotlin.math.max

/**
 * LS invariant for the no-overlap case of [Cumulative] (`unary = true`). Cost, delta and move logic
 * delegate to a unit-capacity [CumulativeInvariant]; only the feasible seeding is its own.
 */
internal class DisjunctiveInvariant(
    private val starts: IntArray,
    private val durations: LongArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val cumulativeBacking: CumulativeInvariant,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) = cumulativeBacking.initialize(state, factorId)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        cumulativeBacking.isViolated(state, factorId)

    /** The total time-overlap energy `Σ_t max(0, concurrency(t) − 1)`. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        cumulativeBacking.violationDegree(state, factorId)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        cumulativeBacking.deltaIfIntSet(state, factorId, intVar, newValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        cumulativeBacking.applyIntSet(state, factorId, intVar, oldValue)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.deltaIfBoolFlipped(state, factorId, boolVar)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.applyBoolFlip(state, factorId, boolVar)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeRepairMoves(state, factorId, sink)

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Compaction, reorders and equal-duration swaps, from the unit-capacity backing. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeStructuredMoves(state, factorId, sink)

    /** Feasible init: left-pack the tasks in earliest-start order, each placed at the first
     *  in-domain time at or after the previous task's end so no two overlap. Returns false —
     *  leaving the random assignment — for the optional form or when a task can't be placed
     *  (domain exhausted, or a frozen start overlaps the packing). */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (presents.isNotEmpty() || starts.isEmpty()) return false
        val order = argsortBy(starts.size) { a, b ->
            state.rootDomains[starts[a]].min.compareTo(state.rootDomains[starts[b]].min)
        }
        var prevEnd = Long.MIN_VALUE
        for (oi in order.indices) {
            val i = order[oi]
            val v = starts[i]
            val dur = durationOf(state, i)
            if (state.assumptions.isFrozenInt(v)) {
                val s = state.assignment.intValue(v)
                if (s < prevEnd) return false
                prevEnd = s + dur
            } else {
                val cand = max(state.rootDomains[v].min, prevEnd)
                val s = firstInDomainAtLeast(state.rootDomains[v], cand) ?: return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    private fun durationOf(state: LocalSearchState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])
}
