package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.argsortByIntKey
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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int =
        cumulativeBacking.deltaIfIntSet(state, factorId, intVar, newValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int =
        cumulativeBacking.applyIntSet(state, factorId, intVar, oldValue)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.deltaIfBoolFlipped(state, factorId, boolVar)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        cumulativeBacking.applyBoolFlip(state, factorId, boolVar)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) =
        cumulativeBacking.proposeRepairMoves(state, factorId, sink)

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: swap the start times of two **equal-duration** tasks.
     *  The pair of occupied intervals `{[s_i, s_i+d), [s_j, s_j+d)}` is exactly preserved (each task
     *  takes the other's slot), so the no-overlap relation with every other task is untouched — only
     *  which task sits in which slot changes. Restricted to the non-optional form. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (presents.isNotEmpty() || starts.size < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < DISJUNCTIVE_STRUCTURED_SWAP_CAP &&
            attempts < DISJUNCTIVE_STRUCTURED_SWAP_CAP * DISJUNCTIVE_SWAP_ATTEMPT_STRIDE
        ) {
            attempts++
            val i = state.rng.nextInt(starts.size)
            val j = state.rng.nextInt(starts.size)
            if (i == j || starts[i] == starts[j]) continue
            if (durationOf(state, i) != durationOf(state, j)) continue
            val si = state.assignment.intValue(starts[i])
            val sj = state.assignment.intValue(starts[j])
            if (si == sj) continue
            if (sj !in state.problem.intDomains[starts[i]] || si !in state.problem.intDomains[starts[j]]) continue
            sink.addCompound(listOf(Move.IntSet(starts[i], sj), Move.IntSet(starts[j], si)))
            emitted++
        }
    }

    /** Feasible init: left-pack the tasks in earliest-start order, each placed at the first
     *  in-domain time at or after the previous task's end so no two overlap. Returns false —
     *  leaving the random assignment — for the optional form or when a task can't be placed
     *  (domain exhausted, or a frozen start overlaps the packing). */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (presents.isNotEmpty() || starts.isEmpty()) return false
        val order = argsortByIntKey(starts.size) { state.problem.intDomains[starts[it]].min }
        var prevEnd = Int.MIN_VALUE
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
                val s = firstInDomainAtLeast(state, v, cand) ?: return false
                state.assignment.setInt(v, s)
                prevEnd = s + dur
            }
        }
        return true
    }

    /** Current duration of task [i]: the constant, or the duration variable's value. */
    private fun durationOf(state: LocalSearchState, i: Int): Int =
        if (durationVars.isEmpty()) durations[i] else state.assignment.intValue(durationVars[i])

    /** Smallest value in [varId]'s domain that is ≥ [lo], or null if none. */
    private fun firstInDomainAtLeast(state: LocalSearchState, varId: Int, lo: Int): Int? {
        val d = state.problem.intDomains[varId]
        if (lo > d.max) return null
        var pick = -1
        d.forEach { if (pick < 0 && it >= lo) pick = it }
        return if (pick < 0) null else pick
    }
}

private const val DISJUNCTIVE_STRUCTURED_SWAP_CAP: Int = 4
private const val DISJUNCTIVE_SWAP_ATTEMPT_STRIDE: Int = 8
