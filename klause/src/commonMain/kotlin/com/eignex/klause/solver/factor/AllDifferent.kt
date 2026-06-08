package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

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
    /** Integer variable ids required to be pairwise distinct. */
    val vars: IntArray,
    /** Minimum value across the shared value domain. */
    val domainMin: Int,
    /** Number of values in the shared value domain. */
    val domainSize: Int,
    /** Per-position presence literals; empty for the non-opt fast path. When non-empty,
     *  only present positions are required pairwise-different, and Régin filtering treats
     *  unpinned-presence positions as "may yet be absent" — they neither demand a matching
     *  slot nor block other positions from claiming their value. */
    val presents: IntArray = EmptyIntArray,
) : LocalSearchFactor {

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
        require(presents.isEmpty() || presents.size == vars.size) {
            "AllDifferent: presents must be empty or match vars arity"
        }
    }

    // Propagation strength: full GAC via Régin's matching + SCC algorithm over the
    // definitely-present positions. IntDomain supports interior holes, so non-matching
    // value pruning lands at the variable domain level. Opt-aware: definitely-absent
    // positions are skipped entirely; unpinned-presence positions are skipped too, so any
    // filtering remains sound under "this position might still go absent".

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = vars

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)

    /** Pre-computed `intVar → number of slots in [vars] holding it`. Used to compute the
     *  delta of changing a single var's value in O(1) without re-scanning [vars]; for the
     *  common case where each var appears exactly once this is always 1. */
    private val occurrencesByVar: IntIntMap = run {
        val counts = HashMap<Int, Int>()
        for (v in vars) counts[v] = (counts[v] ?: 0) + 1
        IntIntMap.build(
            keys = counts.keys.toIntArray(),
            values = counts.values.toIntArray(),
            absent = 0,
        )
    }

    private class State(val counts: IntArray, var duplicateCount: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
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
        for (i in vars.indices) {
            if (!present(state, i)) continue
            val idx = state.assignment.intValue(vars[i]) - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
            if (prev == 1) dups++ // count goes 1 -> 2: new duplicate value.
        }
        state.refPayload[factorId] = State(counts, dups)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.duplicateCount > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val (oldDup, newDup) = simulate(s, presentOccurrences(state, intVar), old, newValue)
        val wasViolated = s.duplicateCount > 0
        val willViolate = (s.duplicateCount + newDup - oldDup) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = s.duplicateCount > 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0 // every occurrence of [intVar] is currently absent.
        val oldIdx = oldValue - domainMin
        val oldCount = s.counts[oldIdx]
        if (oldCount == 2) s.duplicateCount--
        s.counts[oldIdx] = oldCount - n
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

    private fun occurrences(intVar: Int): Int = occurrencesByVar[intVar]

    /** Count of indices where `vars[i] == intVar` AND position `i` is currently present.
     *  Falls back to [occurrences] in the non-opt case for the precomputed O(1) lookup. */
    private fun presentOccurrences(state: LocalSearchState, intVar: Int): Int {
        if (presents.isEmpty()) return occurrencesByVar[intVar]
        var c = 0
        for (i in vars.indices) if (vars[i] == intVar && present(state, i)) c++
        return c
    }

    /** Delta on `duplicateCount` from adjusting a value's count by [delta] (±1). */
    private fun adjustDuplicates(counts: IntArray, valueIdx: Int, delta: Int): Int {
        val before = counts[valueIdx]
        val after = before + delta
        counts[valueIdx] = after
        return when {
            before <= 1 && after >= 2 -> +1
            before >= 2 && after <= 1 -> -1
            else -> 0
        }
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val wasViolated = s.duplicateCount > 0
        // Simulate on a counts snapshot — touch only indices the flip would toggle.
        val touched = mutableListOf<IntArray>() // each: [valueIdx, delta]
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = present(state, i)
            val delta = if (wasP) -1 else +1
            val valueIdx = state.assignment.intValue(vars[i]) - domainMin
            touched += intArrayOf(valueIdx, delta)
        }
        var dupDelta = 0
        val snapshot = s.counts
        for (t in touched) dupDelta += adjustDuplicates(snapshot, t[0], t[1])
        for (k in touched.indices.reversed()) {
            val t = touched[k]
            adjustDuplicates(snapshot, t[0], -t[1])
        }
        val willViolate = (s.duplicateCount + dupDelta) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val wasViolated = s.duplicateCount > 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = present(state, i)
            val delta = if (nowP) +1 else -1
            val valueIdx = state.assignment.intValue(vars[i]) - domainMin
            s.duplicateCount += adjustDuplicates(s.counts, valueIdx, delta)
        }
        val nowViolated = s.duplicateCount > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Hall-style conflict reason: bound + `[v ≠ value]` hole literals confining each
     *  responsible var's domain. Uses the Hall violator [propagate] captured in the
     *  session's [ReginCache] when available — only those vars' domains jointly prove the
     *  pigeonhole, so the others are irrelevant and citing them only over-specialises the
     *  learned clause. Falls back to all vars if no Hall set was recorded (e.g. a failure
     *  path that didn't set it). The scratch lives on the per-session payload, not the
     *  factor, so portfolio workers sharing one Problem never cross reasons (#182). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? ReginCache)?.conflictVars ?: vars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Only the definitely-present positions participate in Régin filtering. Build a
        // local index map: filteredIdx → original position. Unpinned-presence positions
        // are dropped — they may still go absent and would otherwise force unsound prunes.
        val filtered: IntArray = if (presents.isEmpty()) {
            IntArray(vars.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in vars.indices) {
                if (OptPresence.isDefinitelyPresent(presents, i, state)) acc.add(i)
            }
            IntArray(acc.size) { acc[it] }
        }
        val n = filtered.size
        if (n < 2) return true // nothing to filter on a single (or zero) present position.
        val filteredVars = IntArray(n) { vars[filtered[it]] }

        // Régin matching / reverse-reachability / SCC / Hall pruning, shared with the
        // alldifferent_except family via [reginFilter]. No excepted values here. The cache
        // warm-starts the matching across calls (#96).
        val cache = (state.refPayload[factorId] as? ReginCache)
            ?: ReginCache().also { state.refPayload[factorId] = it }
        cache.conflictVars = null // stale-guard; set at the failure point below.
        val hall = reginFilter(state, filteredVars, emptySet(), cache)
        if (hall != null) {
            cache.conflictVars = hall
            return false
        }
        return true
    }

    /* Conservative repair: only act on present occupants when [presents] is set. We
     *  intentionally avoid forcing presence as a repair — the LS engine flips bools via
     *  its own move pool. */

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
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
        // Reservoir-sample one of its occupants (skip absent positions in opt mode).
        var occupant = -1
        var seenOccupants = 0
        for (i in vars.indices) {
            val v = vars[i]
            if (state.assignment.intValue(v) != value) continue
            if (!present(state, i)) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        // Reservoir-sample up to MAX_REPAIR_TARGETS unused targets from the occupant's domain.
        // Giving the strategy a fan of candidates (instead of one) lets WalkSat/probSAT score
        // by break count and pick the move that disturbs the fewest currently-satisfied factors —
        // a real choice rather than coin-flipping a single sampled target.
        val targets = IntArray(MAX_REPAIR_TARGETS) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        // `forEach` skips holes for sparse domains; contiguous fast path is identical
        // to the previous `min..max` walk.
        d.forEach { target ->
            if (target != value) {
                val tIdx = target - domainMin
                if (tIdx in s.counts.indices && s.counts[tIdx] == 0) {
                    seenTargets++
                    if (filled < MAX_REPAIR_TARGETS) {
                        targets[filled++] = target
                    } else {
                        val r = state.rng.nextInt(seenTargets)
                        if (r < MAX_REPAIR_TARGETS) targets[r] = target
                    }
                }
            }
        }
        if (filled > 0) {
            for (i in 0 until filled) sink.addChannelingIntSet(state, occupant, targets[i])
            return
        }
        // No unused targets — every domain value is already taken. A single-var nudge
        // would just shuffle the duplicate. Propose value-swap candidates: pair the
        // occupant with the unique holder of another value in its domain. Within this
        // one AllDifferent the swap preserves the value multiset (so the local duplicate
        // count is unchanged), but in problems with multiple coupled AllDifferents (e.g.
        // Sudoku rows × columns) the swap may resolve a duplicate elsewhere. Cap to
        // [MAX_SWAP_CANDIDATES] — each Compound costs an apply-and-revert in scoring.
        var swapsAdded = 0
        for (w in d.min..d.max) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            if (w == value) continue
            if (w !in d) continue // sparse-aware: skip holes in occupant's domain
            val wIdx = w - domainMin
            if (wIdx !in s.counts.indices || s.counts[wIdx] != 1) continue
            // Locate the unique holder of w. O(|vars|) per candidate; bounded by
            // MAX_SWAP_CANDIDATES so total cost is fixed.
            var holder = -1
            for (v in vars) {
                if (state.assignment.intValue(v) == w) {
                    holder = v
                    break
                }
            }
            if (holder == -1 || holder == occupant) continue
            val hd = state.problem.intDomains[holder]
            if (value !in hd) continue // also sparse-aware on holder's domain
            sink.addCompound(listOf(Move.IntSet(occupant, w), Move.IntSet(holder, value)))
            swapsAdded++
        }
        if (swapsAdded > 0) return
        // Last-resort fallback: nudge occupant by ±1 within domain.
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addChannelingIntSet(state, occupant, cur + 1)
        if (cur > d.min) sink.addChannelingIntSet(state, occupant, cur - 1)
    }

    private companion object {
        /** Cap on candidate targets per repair call. Each candidate adds one O(arity) break-score
         *  evaluation in WalkSat/probSAT, so don't go wild — the fan only needs to be wide enough
         *  for the strategy to discriminate. */
        const val MAX_REPAIR_TARGETS: Int = 4

        /** Cap on swap-pair candidates per call. Each pair requires an O(|vars|) holder lookup
         *  plus the apply-and-revert in [LocalSearchState.evaluateCompound]; two is enough for
         *  the strategy to pick a swap over a single-var move when the domain is saturated. */
        const val MAX_SWAP_CANDIDATES: Int = 2
    }
}
