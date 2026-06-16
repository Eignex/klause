package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/**
 * `intVars(i) != intVars(j)` for every pair `i < j`. Stored payload:
 *
 *   `refPayload(factorId)` = State (counts: IntArray, excess: Int)
 *
 * `counts` is indexed by `value - domainMin` and tracks how many vars currently hold each
 * value across the union domain `[domainMin, domainMin + domainSize)`. `excess` is the graded
 * violation `Σ max(0, count - 1)` — the number of vars that must move to clear every clash; the
 * factor is violated iff that's positive, and it is the [violationDegree] CBLS descends.
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
    override val presents: IntArray = EmptyIntArray,
    /** Values exempt from the distinctness requirement: any number of variables may share a
     *  value in this set (the `alldifferent_except` / `alldifferent_except_0` family, #433).
     *  Empty for plain all-different — then this factor behaves exactly as before. Excepted
     *  values are modelled inside [reginFilter] as capacity-n value copies, so the exact
     *  Hall/matching machinery applies unchanged. */
    val exceptSet: IntArray = EmptyIntArray,
    /** When true, the constraint carried the FlatZinc `::bounds` annotation — the modeller
     *  asked for bounds-consistency rather than full GAC (e.g. ghoulomb's `distinct ::bounds`,
     *  Régin's matching/SCC/Hall machinery is then skipped in favour of a much cheaper
     *  filter, trading pruning strength for per-node throughput as the model intends. */
    val boundsConsistent: Boolean = false,
) : Factor,
    OptionalFactor {

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
        require(presents.isEmpty() || presents.size == vars.size) {
            "AllDifferent: presents must be empty or match vars arity"
        }
    }

    /** Canonical excepted values (deduped, sorted) for [structuralKey] / [remap]. */
    private val exceptSorted: IntArray =
        if (exceptSet.isEmpty()) EmptyIntArray else exceptSet.distinct().sorted().toIntArray()

    /** Membership view of [exceptSet] for the hot value checks; the shared empty set when none. */
    private val exceptValues: IntHashSet =
        if (exceptSet.isEmpty()) NO_EXCEPT else IntHashSet(exceptSet.size).also { s -> for (e in exceptSet) s.add(e) }

    // Propagation strength: full GAC via Régin's matching + SCC algorithm over the
    // definitely-present positions. IntDomain supports interior holes, so non-matching
    // value pruning lands at the variable domain level. Opt-aware: definitely-absent
    // positions are skipped entirely; unpinned-presence positions are skipped too, so any
    // filtering remains sound under "this position might still go absent".

    override fun structuralKey(): String {
        val exceptKey = if (exceptSorted.isEmpty()) "" else ":except=" + exceptSorted.joinToString(",")
        val bcKey = if (boundsConsistent) ":bc" else ""
        return "alldiff:$domainMin:$domainSize:" +
            vars.sorted().joinToString(",") + ":" + presents.sorted().joinToString(",") + exceptKey + bcKey
    }

    /** Plain distinctness ignores which values are used — invariant under any value relabeling
     *  (#366); an excepted-value set names those values, so it is no longer value-anonymous. */
    override fun isValueAnonymous(): Boolean = exceptSet.isEmpty()

    /** Plain all-different names no value, so any relabeling leaves it unchanged; with an
     *  excepted-value set the excepted values are named and must be relabeled too (#374). */
    override fun remapValues(valueMap: (Int) -> Int): Factor = if (exceptSet.isEmpty()) {
        this
    } else {
        AllDifferent(
            vars,
            domainMin,
            domainSize,
            presents,
            IntArray(exceptSet.size) { valueMap(exceptSet[it]) },
            boundsConsistent,
        )
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = AllDifferent(
        vars.remapVars(intMap),
        domainMin,
        domainSize,
        presents.remapLits(boolMap),
        exceptSet,
        boundsConsistent,
    )

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = vars

    /**
     * Advisor subscription (#622): on the bounds-consistency path — and *only* there — this factor
     * reacts purely to the `min`/`max` of its variables ([boundsAllDifferentFilter] never reads
     * interior holes), so it subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] on each
     * variable and is *not* woken by interior [IntEvent.VALUE_REMOVED] carves a co-constraint may
     * punch. A var becoming fixed collapses both bounds, so the FIXED case is covered by the bound
     * kinds without subscribing to [IntEvent.FIXED]. `null` (occurrence-list wakeup, fire on any
     * change) for the full-GAC / optional / excepted paths, which need every value removal.
     */
    override val initialIntEventWatches: IntArray? =
        if (boundsConsistent && presents.isEmpty() && exceptSet.isEmpty()) {
            val distinctVars = vars.distinct()
            val out = IntArray(distinctVars.size * 2)
            var w = 0
            for (v in distinctVars) {
                out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
                out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            }
            out
        } else {
            null
        }

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

    private class State(val counts: IntArray, var excess: Int)

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
        var excess = 0
        for (i in vars.indices) {
            if (!present(state, i)) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue // excepted values may repeat freely.
            val idx = value - domainMin
            val prev = counts[idx]
            counts[idx] = prev + 1
            if (prev >= 1) excess++ // each extra occupant of an already-held value adds one excess.
        }
        state.refPayload[factorId] = State(counts, excess)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.excess > 0
    }

    /** Graded degree: total clash excess `Σ max(0, count - 1)`, run through [compressViolation]
     *  so a wide all-different with many clashes can't dominate the global cost sum. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as State).excess.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0 // every occurrence of [intVar] is currently absent.
        // Excepted values never contribute clash excess, so a move out of / into one is free.
        var rawDelta = 0
        if (old !in exceptValues) {
            val oldCount = s.counts[old - domainMin]
            rawDelta += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (newValue !in exceptValues) {
            val newCount = s.counts[newValue - domainMin]
            rawDelta += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation((s.excess + rawDelta).toLong(), state.violationSoftCap) -
            compressViolation(s.excess.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val n = presentOccurrences(state, intVar)
        if (n == 0) return 0 // every occurrence of [intVar] is currently absent.
        val before = s.excess
        // Excepted values are kept out of counts/excess entirely (they may repeat freely).
        if (oldValue !in exceptValues) {
            val oldIdx = oldValue - domainMin
            val oldCount = s.counts[oldIdx]
            s.counts[oldIdx] = oldCount - n
            s.excess += excessOf(oldCount - n) - excessOf(oldCount)
        }
        if (cur !in exceptValues) {
            val newIdx = cur - domainMin
            val newCount = s.counts[newIdx]
            s.counts[newIdx] = newCount + n
            s.excess += excessOf(newCount + n) - excessOf(newCount)
        }
        return compressViolation(s.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /** Clash excess contributed by a single value held by [count] vars: `max(0, count - 1)`. */
    private fun excessOf(count: Int): Int = if (count > 1) count - 1 else 0

    /** Count of indices where `vars(i) == intVar` AND position `i` is currently present.
     *  Falls back to [occurrencesByVar] in the non-opt case for the precomputed O(1) lookup. */
    private fun presentOccurrences(state: LocalSearchState, intVar: Int): Int {
        if (presents.isEmpty()) return occurrencesByVar[intVar]
        var c = 0
        for (i in vars.indices) if (vars[i] == intVar && present(state, i)) c++
        return c
    }

    /** Mutate `counts[valueIdx]` by [delta] (±1) and return the resulting change in clash
     *  excess `Σ max(0, count - 1)`. */
    private fun adjustExcess(counts: IntArray, valueIdx: Int, delta: Int): Int {
        val before = counts[valueIdx]
        val after = before + delta
        counts[valueIdx] = after
        return excessOf(after) - excessOf(before)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        // Simulate on a counts snapshot — touch only indices the flip would toggle. Parallel
        // primitive lists (valueIdx, delta) avoid boxing an IntArray pair per touched position.
        val touchedIdx = IntArrayList()
        val touchedDelta = IntArrayList()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue // excepted values never contribute excess.
            val wasP = present(state, i)
            val delta = if (wasP) -1 else +1
            touchedIdx.add(value - domainMin)
            touchedDelta.add(delta)
        }
        var excessDelta = 0
        val snapshot = s.counts
        for (k in 0 until touchedIdx.size) excessDelta += adjustExcess(snapshot, touchedIdx[k], touchedDelta[k])
        for (k in touchedIdx.size - 1 downTo 0) adjustExcess(snapshot, touchedIdx[k], -touchedDelta[k])
        return compressViolation((s.excess + excessDelta).toLong(), state.violationSoftCap) -
            compressViolation(s.excess.toLong(), state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val before = s.excess
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val value = state.assignment.intValue(vars[i])
            if (value in exceptValues) continue // excepted values never contribute excess.
            val nowP = present(state, i)
            val delta = if (nowP) +1 else -1
            s.excess += adjustExcess(s.counts, value - domainMin, delta)
        }
        return compressViolation(s.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /** Hall-style conflict reason: bound + `[v ≠ value]` hole literals confining each
     *  responsible var's domain. Uses the Hall violator [propagate] captured in the
     *  session's [ReginCache] when available — only those vars' domains jointly prove the
     *  pigeonhole, so the others are irrelevant and citing them only over-specialises the
     *  learned clause. Falls back to all vars if no Hall set was recorded (e.g., a failure
     *  path that didn't set it). The scratch lives on the per-session payload, not the
     *  factor, so portfolio workers sharing one Problem never cross reasons (#182). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? ReginCache)?.conflictVars ?: vars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Bounds-consistency fast path for plain all-different (no optional positions, no
        // excepted values — both of which need the Régin value graph). Far cheaper per node.
        if (boundsConsistent && presents.isEmpty() && exceptSet.isEmpty()) {
            val hall = boundsAllDifferentFilter(state, vars)
            if (hall != null) {
                val cache = (state.refPayload[factorId] as? ReginCache)
                    ?: ReginCache().also { state.refPayload[factorId] = it }
                cache.conflictVars = hall
                return false
            }
            return true
        }
        // Only the definitely-present positions participate in Régin filtering. Build a
        // local index map: filteredIdx → original position. Unpinned-presence positions
        // are dropped — they may still go absent and would otherwise force unsound prunes.
        val filtered: IntArray = if (presents.isEmpty()) {
            IntArray(vars.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in vars.indices) {
                if (definitelyPresent(i, state)) acc.add(i)
            }
            IntArray(acc.size) { acc[it] }
        }
        val n = filtered.size
        if (n < 2) return true // nothing to filter on a single (or zero) present position.
        val filteredVars = IntArray(n) { vars[filtered[it]] }

        // Régin matching / reverse-reachability / SCC / Hall pruning via [reginFilter].
        // [exceptValues] is empty for plain all-different. The cache warm-starts the matching
        // across calls (#96).
        val cache = (state.refPayload[factorId] as? ReginCache)
            ?: ReginCache().also { state.refPayload[factorId] = it }
        cache.conflictVars = null // stale-guard; set at the failure point below.
        val hall = reginFilter(state, filteredVars, exceptValues, cache)
        if (hall != null) {
            cache.conflictVars = hall
            return false
        }
        return true
    }

    /* Conservative repair: only act on present occupants when [presents] is set. We
     *  intentionally avoid forcing presence as a repair, the LS engine flips bools via
     *  its own move pool. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.excess == 0) return
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
        // count is unchanged), but in problems with multiple coupled AllDifferents
        // (e.g., Sudoku rows × columns) the swap may resolve a duplicate elsewhere.
        // Cap to // [MAX_SWAP_CANDIDATES]; each Compound costs an apply-and-revert in scoring.
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

    /** Feasibility-preserving neighbourhood: when the factor is satisfied every present var
     *  holds a distinct value, so swapping the values of two present vars keeps the multiset
     *  and therefore distinctness intact. Each swap is offered as a compound the engine scores
     *  against coupled constraints — within one all-different it is cost-neutral, but on shared
     *  scopes (Sudoku rows × columns, timetabling) it can relocate a value to clear a clash
     *  elsewhere without ever breaking this constraint. */
    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (vars.size < 2) return
        var emitted = 0
        var attempts = 0
        val cap = MAX_STRUCTURED_SWAPS
        while (emitted < cap && attempts < cap * SWAP_ATTEMPT_STRIDE) {
            attempts++
            val ai = state.rng.nextInt(vars.size)
            val bi = state.rng.nextInt(vars.size)
            val a = vars[ai]
            val b = vars[bi]
            if (a == b) continue
            if (!present(state, ai) || !present(state, bi)) continue
            val va = state.assignment.intValue(a)
            val vb = state.assignment.intValue(b)
            if (va == vb) continue // nothing to swap (e.g. both excepted and equal).
            if (vb !in state.problem.intDomains[a]) continue
            if (va !in state.problem.intDomains[b]) continue
            sink.addCompound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
            emitted++
        }
    }

    private companion object {
        /** Empty excepted-value set for the shared [reginFilter] (plain alldifferent has none).
         *  [reginFilter] only reads it, so one shared immutable-in-practice instance is safe. */
        val NO_EXCEPT = IntHashSet()

        /** Cap on feasibility-preserving swap pairs offered per [proposeStructuredMoves] call.
         *  Each compound costs an apply-and-revert in scoring, so keep the fan small. */
        const val MAX_STRUCTURED_SWAPS: Int = 4

        /** Rejection-sampling attempts allowed per requested swap before giving up (saturated or
         *  domain-incompatible pairs are skipped). Bounds the loop on tight/heterogeneous domains. */
        const val SWAP_ATTEMPT_STRIDE: Int = 6

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
