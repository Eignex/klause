package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * `intVars[i] != intVars[j]` for every pair `i < j`. Stored payload:
 *
 *   refPayload[factorId] = State (counts: HashMap<Int, Int>, var duplicateCount: Int)
 *
 * `counts` maps each value currently held by some var in [vars] to the number of vars holding
 * it; `duplicateCount` is the number of distinct values whose count is > 1, i.e. the number of
 * "duplicated" values. The factor is violated iff `duplicateCount > 0`.
 */
class AllDifferent(
    val vars: IntArray,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    init { require(vars.size >= 2) { "AllDifferent needs at least two variables" } }

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = vars

    private class State(val counts: HashMap<Int, Int>, var duplicateCount: Int)

    override fun initialize(state: SolverState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var dups = 0
        for (v in vars) {
            val value = state.assignment.intValue(v)
            val prev = counts.getOrElse(value) { 0 }
            val next = prev + 1
            counts[value] = next
            if (prev == 1) dups++   // count goes 1 -> 2: new duplicate value.
        }
        state.refPayload[factorId] = State(counts, dups)
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.duplicateCount > 0
    }

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val (oldDup, newDup) = simulate(s, vars.count { it == intVar }, old, newValue)
        val wasViolated = s.duplicateCount > 0
        val willViolate = (s.duplicateCount + newDup - oldDup) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.duplicateCount > 0
        val occurrences = vars.count { it == intVar }
        // Decrement count for oldValue.
        val oldCount = s.counts.getOrElse(oldValue) { 0 }
        if (oldCount == 2) s.duplicateCount--
        val oldRem = oldCount - occurrences
        if (oldRem <= 0) s.counts.remove(oldValue) else s.counts[oldValue] = oldRem
        // Increment count for newValue.
        val newCount = s.counts.getOrElse(cur) { 0 }
        val newPlus = newCount + occurrences
        s.counts[cur] = newPlus
        if (newCount <= 1 && newPlus >= 2) s.duplicateCount++
        val nowViolated = s.duplicateCount > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Compute (oldDuplicateDelta, newDuplicateDelta) without mutating state. Used by
     *  deltaIfIntSet for in-place violation deltas. */
    private fun simulate(s: State, occurrences: Int, oldValue: Int, newValue: Int): Pair<Int, Int> {
        if (oldValue == newValue) return 0 to 0
        val oldCount = s.counts.getOrElse(oldValue) { 0 }
        val newCount = s.counts.getOrElse(newValue) { 0 }
        var lostDup = 0
        var gainedDup = 0
        // Decrement oldValue by occurrences.
        if (oldCount >= 2 && oldCount - occurrences <= 1) lostDup = 1
        // Increment newValue by occurrences.
        if (newCount <= 1 && newCount + occurrences >= 2) gainedDup = 1
        return lostDup to gainedDup
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.duplicateCount == 0) return
        // Find a duplicated value, pick one of its occupants, propose snapping it to a value
        // currently absent from the assignment.
        for ((value, count) in s.counts) {
            if (count <= 1) continue
            // Pick the first var holding this value.
            var occupant = -1
            for (v in vars) if (state.assignment.intValue(v) == value) { occupant = v; break }
            if (occupant == -1) continue
            val d = state.problem.intDomains[occupant]
            // Try every domain value; propose a snap to the first one that's currently free.
            for (target in d.min..d.max) {
                if (target == value) continue
                if (s.counts.getOrElse(target) { 0 } == 0) {
                    sink.addIntSet(occupant, target)
                    return
                }
            }
            // Fallback: nudge occupant by ±1.
            val cur = state.assignment.intValue(occupant)
            if (cur < d.max) sink.addIntSet(occupant, cur + 1)
            if (cur > d.min) sink.addIntSet(occupant, cur - 1)
            return
        }
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
