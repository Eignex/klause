package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * Generalised `alldifferent_except(xs, except)` — `xs[i] != xs[j]` for every pair `i < j`
 * unless one of the two values is in [except]. The classic `AllDifferentExceptZero` is the
 * `except = {0}` specialisation; this factor uses a HashSet membership check so propagation
 * over arbitrary excluded-value sets remains O(N · |except|) per call.
 *
 * Propagation is the same singleton-take filter as the zero-only variant: any var pinned to
 * a non-excluded value `v` removes `v` from every other var's domain.
 */
class AllDifferentExcept(
    /** Integer variable ids required to be pairwise distinct outside [except]. */
    val xs: IntArray,
    except: IntArray,
) : LocalSearchFactor {

    val except: IntArray = except.distinct().sorted().toIntArray()
    private val exceptSet: IntHashSet = run {
        val s = IntHashSet(except.size)
        for (e in except) s.add(e)
        s
    }

    init {
        require(xs.size >= 2) { "AllDifferentExcept needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Per-value count among non-excluded values. `violatedPairs` is the number of (i, j) with
     *  i < j and `xs[i]` = `xs[j]` ∉ except. */
    private class State(val counts: MutableIntIntMap, var violatedPairs: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = MutableIntIntMap()
        var bad = 0
        for (v in xs) {
            val value = state.assignment.intValue(v)
            if (value in exceptSet) continue
            val prev = counts.getOrDefault(value, 0)
            counts.put(value, prev + 1)
            if (prev >= 1) bad++
        }
        state.refPayload[factorId] = State(counts, bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violatedPairs > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        var bad = s.violatedPairs
        if (old !in exceptSet) {
            val cnt = s.counts.getOrDefault(old, 0)
            val after = cnt - occurrences
            bad -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
        }
        if (newValue !in exceptSet) {
            val cnt = s.counts.getOrDefault(newValue, 0)
            val after = cnt + occurrences
            bad += pairsAt(after) - pairsAt(cnt)
        }
        val wasViolated = s.violatedPairs > 0
        val willViolate = bad > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        val wasViolated = s.violatedPairs > 0
        if (oldValue !in exceptSet) {
            val cnt = s.counts.getOrDefault(oldValue, 0)
            val after = cnt - occurrences
            s.violatedPairs -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
            if (after <= 0) s.counts.remove(oldValue) else s.counts.put(oldValue, after)
        }
        if (cur !in exceptSet) {
            val cnt = s.counts.getOrDefault(cur, 0)
            val after = cnt + occurrences
            s.violatedPairs += pairsAt(after) - pairsAt(cnt)
            s.counts.put(cur, after)
        }
        val nowViolated = s.violatedPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun pairsAt(k: Int): Int = if (k <= 1) 0 else k * (k - 1) / 2

    // The Hall-violator vars behind the last propagate failure live in the session's
    // ReginCache (null for a singleton-clash / no-capture path), so the reason cites just
    // the responsible set and portfolio workers sharing one Problem never cross reasons
    // (#182).
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? ReginCache)?.conflictVars ?: xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Stale-guard up front: phase 1 can fail before the matching pass, and the reason
        // fallback (all of xs) must not be shadowed by a previous failure's Hall set.
        val cache = (state.refPayload[factorId] as? ReginCache)
            ?: ReginCache().also { state.refPayload[factorId] = it }
        cache.conflictVars = null // set at the matching-pass failure point.
        // Phase 1: singleton-take filter (cheap, runs first).
        val taken = IntHashSet()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (d.min in exceptSet) continue
            if (!taken.add(d.min)) return false
        }
        if (!taken.isEmpty()) {
            val ant = state.composeIntVarAtomAntecedents(xs)
            for (v in xs) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                taken.forEach { t ->
                    if (t in d.min..d.max && !state.excludeIntValue(v, t, ant)) return false
                }
            }
        }
        // Phase 2: shared Régin matching-and-SCC pruning ([reginFilter]); excepted values are
        // modelled as capacity-n copies. The cache warm-starts the matching across calls (#96).
        val hall = reginFilter(state, xs, exceptSet, cache)
        if (hall != null) {
            cache.conflictVars = hall
            return false
        }
        return true
    }

    /** Reservoir-sample a duplicated value uniformly across all duplicates, then reservoir-
     *  sample one of its occupants, then propose multiple candidate targets: the excluded
     *  sentinels (which never break the constraint) plus reservoir-sampled in-domain
     *  unused values. Mirrors the structure of [AllDifferent.proposeRepairMoves] so the
     *  except variant has the same search diversity as the base. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.violatedPairs == 0) return
        // Reservoir-sample a duplicated value.
        var pickedValue = Int.MIN_VALUE
        var seenDups = 0
        s.counts.forEach { value, count ->
            if (count >= 2) {
                seenDups++
                if (state.rng.nextInt(seenDups) == 0) pickedValue = value
            }
        }
        if (pickedValue == Int.MIN_VALUE) return
        // Reservoir-sample one occupant of that value.
        var occupant = -1
        var seenOccupants = 0
        for (v in xs) {
            if (state.assignment.intValue(v) != pickedValue) continue
            seenOccupants++
            if (state.rng.nextInt(seenOccupants) == 0) occupant = v
        }
        if (occupant == -1) return
        val d = state.problem.intDomains[occupant]
        // Excluded sentinels first — they're always safe targets.
        var emitted = 0
        for (e in except) {
            if (e in d && e != pickedValue) {
                sink.addChannelingIntSet(state, occupant, e)
                if (++emitted >= MAX_REPAIR_TARGETS) return
            }
        }
        // Reservoir-sample unused (count == 0) targets from the occupant's domain. Cap at
        // MAX_REPAIR_TARGETS combined with the sentinel emissions above so the proposal
        // set stays bounded.
        val budget = MAX_REPAIR_TARGETS - emitted
        if (budget <= 0) return
        val targets = IntArray(budget) { Int.MIN_VALUE }
        var filled = 0
        var seenTargets = 0
        d.forEach { target ->
            if (target == pickedValue) return@forEach
            if (target in exceptSet) return@forEach // already proposed above
            val count = s.counts.getOrDefault(target, 0)
            if (count != 0) return@forEach
            seenTargets++
            if (filled < budget) {
                targets[filled++] = target
            } else {
                val r = state.rng.nextInt(seenTargets)
                if (r < budget) targets[r] = target
            }
        }
        for (i in 0 until filled) sink.addChannelingIntSet(state, occupant, targets[i])
    }

    private companion object {
        /** Cap on total target candidates proposed per call. Matches AllDifferent.MAX_REPAIR_TARGETS. */
        const val MAX_REPAIR_TARGETS: Int = 4
    }
}
