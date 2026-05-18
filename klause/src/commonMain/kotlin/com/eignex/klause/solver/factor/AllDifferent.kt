package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.LocalSearchState

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

    // Propagation strength: bound-consistent (Puget-style) via Hall-interval detection.
    // Full Régin GAC would punch holes in non-contiguous interior domains, but klause's
    // [com.eignex.klause.solver.IntDomain] is a contiguous interval — endpoint tightening
    // is the strongest representable inference, so bound consistency is the theoretical
    // ceiling here. Régin SCC residual reasoning would only help if IntDomain ever grew
    // a sparse-value representation.

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = vars

    /** Pre-computed `intVar → number of slots in [vars] holding it`. Used to compute the
     *  delta of changing a single var's value in O(1) without re-scanning [vars]; for the
     *  common case where each var appears exactly once this is always 1. */
    private val occurrencesByVar: com.eignex.klause.util.IntIntMap = run {
        val counts = HashMap<Int, Int>()
        for (v in vars) counts[v] = (counts[v] ?: 0) + 1
        com.eignex.klause.util.IntIntMap.build(
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
        for (v in vars) {
            val idx = state.assignment.intValue(v) - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
            if (prev == 1) dups++   // count goes 1 -> 2: new duplicate value.
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
        val (oldDup, newDup) = simulate(s, occurrences(intVar), old, newValue)
        val wasViolated = s.duplicateCount > 0
        val willViolate = (s.duplicateCount + newDup - oldDup) > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
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

    private fun occurrences(intVar: Int): Int = occurrencesByVar[intVar]

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // ---- 1. Singleton conflicts. ----------------------------------------------------
        val taken = HashSet<Int>()
        for (v in vars) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        // ---- 2. Remove singleton-taken values from every other var's domain. ----------
        // With sparse domain support, this is full GAC on singleton conflicts (not just
        // endpoint shaving): if a singleton var holds value `t`, every non-singleton var's
        // domain has `t` punched out — even when `t` is interior to that var's range.
        if (taken.isNotEmpty()) {
            for (v in vars) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                for (t in taken) {
                    // Skip values outside this var's bounds — quick rejection for the common
                    // case where many singletons are far from any given var's domain.
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(v, t)) return false
                }
            }
        }
        // ---- 3. Hall-interval detection (bound consistency). ---------------------------
        // For each candidate interval [a, b] over var-endpoint pairs, count vars whose
        // *entire* domain falls inside it. Two cases of interest:
        //   count > span  → pigeonhole infeasibility, [a, b] can't host that many vars.
        //   count = span  → Hall interval: the vars inside it monopolise all `span`
        //                   values, so other vars get any overlap with [a, b] pruned.
        //
        // Endpoint enumeration is complete for Hall-interval discovery: any Hall interval
        // [a, b] has a equal to some var's domain min (extending leftward only loosens
        // membership) and b equal to some var's domain max. We collect distinct min /
        // max values and iterate the cartesian product. Cost is O(|mins| * |maxes| * n);
        // for typical AllDifferent sizes (n ≤ ~50) this is well within budget.
        val mins = HashSet<Int>()
        val maxes = HashSet<Int>()
        for (v in vars) {
            val d = state.intDomains[v]
            mins.add(d.min)
            maxes.add(d.max)
        }
        for (a in mins) {
            for (b in maxes) {
                if (b < a) continue
                var count = 0
                for (v in vars) {
                    val d = state.intDomains[v]
                    if (d.min >= a && d.max <= b) count++
                }
                val span = b - a + 1
                if (count > span) return false
                if (count == span && count > 0 && count < vars.size) {
                    // Hall interval — prune from non-Hall-set vars. Three sub-cases:
                    //  - var's domain fully inside [a, b] → it's a Hall-set member, skip.
                    //  - var's max ∈ [a, b] (left-overlap)          → tighten max down to a - 1.
                    //  - var's min ∈ [a, b] (right-overlap)         → tighten min up to b + 1.
                    //  - var spans across (min < a AND max > b)     → punch every Hall
                    //    value out individually via excludeIntValue (sparse domain
                    //    representation handles the interior holes).
                    for (v in vars) {
                        val d = state.intDomains[v]
                        if (d.min >= a && d.max <= b) continue
                        when {
                            d.max in a..b -> {
                                if (!state.tightenIntMax(v, a - 1)) return false
                            }
                            d.min in a..b -> {
                                if (!state.tightenIntMin(v, b + 1)) return false
                            }
                            d.min < a && d.max > b -> {
                                // Spanning across; punch every Hall value out.
                                for (h in a..b) {
                                    if (h !in d) continue
                                    if (!state.excludeIntValue(v, h)) return false
                                }
                            }
                            // else: no overlap with [a, b] at all → nothing to do.
                        }
                    }
                }
            }
        }
        // ---- 4. Global pigeonhole (cheap last-line check). ------------------------------
        // After Hall pruning the per-interval pigeonhole would catch any remaining
        // infeasibility, but the old "available values across all non-pinned vars" check
        // is cheap and catches the universal-Hall case (k = nonPinned, [a, b] spanning
        // everything) when Hall enumeration above happened to miss it because the union
        // isn't contiguous. Vars can have wider domains than the declared union
        // [domainMin, domainMin+domainSize) at Problem-construction time (full alignment
        // is asserted only at LocalSearchState init), so clip each var's effective domain
        // to the declared union before tallying.
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
        // Reservoir-sample up to MAX_REPAIR_TARGETS unused targets from the occupant's domain.
        // Giving the strategy a fan of candidates (instead of one) lets WalkSat/probSAT score
        // by break count and pick the move that disturbs the fewest currently-satisfied factors —
        // a real choice rather than coin-flipping a single sampled target.
        val targets = IntArray(MAX_REPAIR_TARGETS) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        for (target in d.min..d.max) {
            if (target == value) continue
            val tIdx = target - domainMin
            if (tIdx !in s.counts.indices || s.counts[tIdx] != 0) continue
            seenTargets++
            if (filled < MAX_REPAIR_TARGETS) {
                targets[filled++] = target
            } else {
                // Reservoir replace: each subsequent target replaces a slot uniformly.
                val r = state.rng.nextInt(seenTargets)
                if (r < MAX_REPAIR_TARGETS) targets[r] = target
            }
        }
        if (filled > 0) {
            for (i in 0 until filled) sink.addIntSet(occupant, targets[i])
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
            val wIdx = w - domainMin
            if (wIdx !in s.counts.indices || s.counts[wIdx] != 1) continue
            // Locate the unique holder of w. O(|vars|) per candidate; bounded by
            // MAX_SWAP_CANDIDATES so total cost is fixed.
            var holder = -1
            for (v in vars) if (state.assignment.intValue(v) == w) { holder = v; break }
            if (holder == -1 || holder == occupant) continue
            val hd = state.problem.intDomains[holder]
            if (value < hd.min || value > hd.max) continue
            sink.addCompound(listOf(Move.IntSet(occupant, w), Move.IntSet(holder, value)))
            swapsAdded++
        }
        if (swapsAdded > 0) return
        // Last-resort fallback: nudge occupant by ±1 within domain.
        val cur = state.assignment.intValue(occupant)
        if (cur < d.max) sink.addIntSet(occupant, cur + 1)
        if (cur > d.min) sink.addIntSet(occupant, cur - 1)
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
