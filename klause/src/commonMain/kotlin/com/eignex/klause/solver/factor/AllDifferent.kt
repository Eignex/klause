package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.SolverState

/**
 * `intVars[i] != intVars[j]` for every pair `i < j`. Stored payload:
 *
 *   refPayload[factorId] = State (counts: IntArray, duplicateCount: Int)
 *
 * `counts` is indexed by `value - domainMin` and tracks how many vars currently hold each
 * value across the union domain `[domainMin, domainMin + domainSize)`. `duplicateCount` is the
 * number of distinct values whose count is > 1; the factor is violated iff that's positive.
 */
class AllDifferent(
    val vars: IntArray,
    val domainMin: Int,
    val domainSize: Int,
) : LocalSearchFactor {

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
    }

    // TODO(propagate): full Hall-set / matching-based arc consistency (e.g. Régin's algorithm).
    //  Current impl catches the simple cases — singleton conflicts, endpoint shaving, and the
    //  global pigeonhole check (#available-values ≥ #non-pinned-vars) — but does not find
    //  interior Hall sets to prune interior domain values.

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = vars

    private class State(val counts: IntArray, var duplicateCount: Int)

    override fun initialize(state: SolverState, factorId: Int) {
        // Sanity: every operand's domain must lie within the declared union range.
        for (v in vars) {
            val d = state.problem.intDomains[v]
            require(d.min >= domainMin && d.max < domainMin + domainSize) {
                "AllDifferent var $v has domain $d outside declared union " +
                    "[$domainMin..${domainMin + domainSize - 1}]"
            }
        }
        val counts = IntArray(domainSize)
        var dups = 0
        for (v in vars) {
            val idx = state.assignment.intValue(v) - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
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
        val (oldDup, newDup) = simulate(s, occurrences(intVar), old, newValue)
        val wasViolated = s.duplicateCount > 0
        val willViolate = (s.duplicateCount + newDup - oldDup) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.duplicateCount > 0
        val n = occurrences(intVar)
        // Decrement count for oldValue.
        val oldIdx = oldValue - domainMin
        val oldCount = s.counts[oldIdx]
        if (oldCount == 2) s.duplicateCount--
        s.counts[oldIdx] = oldCount - n
        // Increment count for newValue.
        val newIdx = cur - domainMin
        val newCount = s.counts[newIdx]
        val newPlus = newCount + n
        s.counts[newIdx] = newPlus
        if (newCount <= 1 && newPlus >= 2) s.duplicateCount++
        val nowViolated = s.duplicateCount > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Compute (oldDuplicateDelta, newDuplicateDelta) without mutating state. */
    private fun simulate(s: State, occurrences: Int, oldValue: Int, newValue: Int): Pair<Int, Int> {
        if (oldValue == newValue) return 0 to 0
        val oldCount = s.counts[oldValue - domainMin]
        val newCount = s.counts[newValue - domainMin]
        var lostDup = 0
        var gainedDup = 0
        if (oldCount >= 2 && oldCount - occurrences <= 1) lostDup = 1
        if (newCount <= 1 && newCount + occurrences >= 2) gainedDup = 1
        return lostDup to gainedDup
    }

    private fun occurrences(intVar: Int): Int {
        var n = 0
        for (v in vars) if (v == intVar) n++
        return n
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Build set of "taken" values: those held by any singleton-domain var. Two vars with
        // the same singleton value → Unsat.
        val taken = HashSet<Int>()
        for (v in vars) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        // For each non-singleton var, shave taken values off domain endpoints (repeatedly).
        // Only worth scanning when at least one value is taken.
        if (taken.isNotEmpty()) {
            for (v in vars) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                var lo = d.min
                var hi = d.max
                while (lo <= hi && lo in taken) lo++
                while (hi >= lo && hi in taken) hi--
                if (lo > hi) return false
                if (lo != d.min && !state.tightenIntMin(v, lo)) return false
                if (hi != d.max && !state.tightenIntMax(v, hi)) return false
            }
        }
        // Pigeonhole: across non-pinned vars, count distinct values still available (i.e.,
        // values lying in some non-pinned var's tightened domain and not in [taken]). If that
        // count is less than the number of non-pinned vars, no injective assignment exists.
        //
        // Vars can have wider domains than the declared union [domainMin, domainMin+domainSize)
        // at Problem-construction time (full alignment is asserted only at SolverState init).
        // Clip each var's effective domain to the declared union before tallying.
        val domainMax = domainMin + domainSize - 1
        val covered = BooleanArray(domainSize)
        var nonPinned = 0
        for (v in vars) {
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            nonPinned++
            val lo = maxOf(d.min, domainMin)
            val hi = minOf(d.max, domainMax)
            for (value in lo..hi) {
                if (value in taken) continue
                covered[value - domainMin] = true
            }
        }
        if (nonPinned > 0) {
            var available = 0
            for (c in covered) if (c) available++
            if (available < nonPinned) return false
        }
        return true
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.duplicateCount == 0) return
        // Reservoir-sample a duplicated value (uniform across all values whose count > 1).
        var pickedIdx = -1
        var seenDups = 0
        for (idx in s.counts.indices) {
            if (s.counts[idx] <= 1) continue
            seenDups++
            if (state.rng.nextInt(seenDups) == 0) pickedIdx = idx
        }
        if (pickedIdx == -1) return
        val value = pickedIdx + domainMin
        // Reservoir-sample one of its occupants.
        var occupant = -1
        var seenOccupants = 0
        for (v in vars) {
            if (state.assignment.intValue(v) != value) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        // Reservoir-sample a target value not currently used.
        var pickedTarget = Int.MIN_VALUE
        var seenTargets = 0
        for (target in d.min..d.max) {
            if (target == value) continue
            val tIdx = target - domainMin
            if (tIdx !in s.counts.indices || s.counts[tIdx] != 0) continue
            seenTargets++
            if (state.rng.nextInt(seenTargets) == 0) pickedTarget = target
        }
        if (pickedTarget != Int.MIN_VALUE) {
            sink.addIntSet(occupant, pickedTarget)
            return
        }
        // Fallback: nudge occupant by ±1.
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addIntSet(occupant, cur + 1)
        if (cur > d.min) sink.addIntSet(occupant, cur - 1)
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
