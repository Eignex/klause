package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.scheduling.internals.firstInDomainAtLeast
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.argsortBy
import kotlin.math.max

/**
 * LS invariant for [Disjunctive]. Constructed by [Disjunctive.asInvariant] and delegates
 * all cost / delta / repair logic to an internal [CumulativeInvariant] backing at capacity = 1,
 * unit resources.
 */
internal class DisjunctiveInvariant(
    private val starts: IntArray,
    private val durations: IntArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    /** Unit-capacity CumulativeInvariant backing for LS cost and repair moves. */
    private val cumulativeBacking: CumulativeInvariant,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) = cumulativeBacking.initialize(state, factorId)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        cumulativeBacking.isViolated(state, factorId)

    /** Graded violation: delegates to the unit-capacity [CumulativeInvariant] backing, so the degree is
     *  the total time-overlap energy `Σ_t max(0, concurrency_t − 1)` — a real gradient. */
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

    /** Feasibility-preserving neighbourhood: delegates to the unit-capacity [CumulativeInvariant] backing,
     *  which swaps equal-duration, equal-resource (unit) task pairs. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeStructuredMoves(state, factorId, sink)

    /** Feasible init: left-pack the tasks in earliest-start order, each placed at the first
     *  in-domain time at or after the previous task's end so no two overlap. Returns false —
     *  leaving the random assignment — for the optional form or when a task can't be placed
     *  (domain exhausted, or a frozen start overlaps the packing). */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (presents.isNotEmpty() || starts.isEmpty()) return false
        val order = argsortBy(starts.size) { a, b ->
            state.problem.intDomains[starts[a]].min.compareTo(state.problem.intDomains[starts[b]].min)
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
                val cand = max(state.problem.intDomains[v].min, prevEnd)
                val s = firstInDomainAtLeast(state.problem.intDomains[v], cand) ?: return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    /** Current duration of task [i]: the constant, or the duration variable's value. */
    private fun durationOf(state: LocalSearchState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i].toLong() else state.assignment.intValue(durationVars[i])
}
