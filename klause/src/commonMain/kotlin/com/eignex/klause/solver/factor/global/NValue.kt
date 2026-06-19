package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.OptionalFactor
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.linear.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * `nvalue(n, xs)` — `n` equals the count of distinct values appearing in [xs]. Plus
 * variants:
 *
 *  - [Mode.Eq] (default): `n = |distinct(xs)|`.
 *  - [Mode.AtLeast]: `n ≤ |distinct(xs)|`.
 *  - [Mode.AtMost]:  `n ≥ |distinct(xs)|`.
 *
 * One factor with a mode flag so all three MiniZinc predicates (`fzn_nvalue`,
 * `fzn_atleast_nvalues`, `fzn_atmost_nvalues`) lower to the same factor type.
 */
class NValue(
    /** Integer variable id holding the distinct-value count target. */
    val n: Int,
    /** Integer variable ids whose distinct values are counted. */
    val xs: IntArray,
    /** How [n] relates to the actual distinct-value count. */
    val mode: Mode = Mode.Eq,
    /** Per-index presence literals; empty for the non-opt fast path. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor {

    /** How an `nvalue` constraint's target relates to the actual distinct-value count. */
    enum class Mode {
        /** Distinct count equals [n]. */
        Eq,

        /** Distinct count is at least [n]. */
        AtLeast,

        /** Distinct count is at most [n]. */
        AtMost,
    }

    init {
        require(xs.isNotEmpty()) { "nvalue: empty xs" }
        require(presents.isEmpty() || presents.size == xs.size) {
            "nvalue: presents must be empty or match xs arity"
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        NValue(intMap[n], xs.remapVars(intMap), mode, presents.remapLits(boolMap))

    /** The distinct-value count ignores the order of [xs], so the counted vars are sorted (paired with
     *  their presence literal to keep an opt position with its presence); [n] (the count var) and
     *  [mode] are positional constants (#443). */
    override fun structuralKey(): String = "nvalue:$mode:$n:" +
        xs.indices.sortedBy { xs[it] }.joinToString(",") { "${xs[it]}/${presents.getOrElse(it) { -1 }}" }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = xs + intArrayOf(n)

    /** Advisor subscription (#623) for the non-optional variant: the distinct-count bounds read each
     *  variable's full domain (union membership + domain-overlap disjointness), so subscribe to every
     *  kind and consume the dirty-variable delta (#624) to skip fires where nothing changed. The
     *  optional variant keeps occurrence wakeup — a presence-bool flip changes the count but is not in
     *  the int-domain delta, so it must not be gated out. */
    override val initialIntEventWatches: IntArray? = if (presents.isNotEmpty()) {
        null
    } else {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = presents.isEmpty()

    /** CP-side gate flag for the delta fast path (non-optional variant). Lives in the propagation
     *  state's refPayload, distinct from the local-search [State]. Drifts across push/pop — a stale
     *  `true` is harmless because any forward narrowing re-wakes via its event, and the bounds are
     *  recomputed from the (engine-restored) current domains. */
    private class CpGate {
        var started: Boolean = false
    }

    /** Maintains a per-value count over the assignment. `distinctCount` = number of values
     *  whose count is > 0. */
    private class State(val counts: MutableIntIntMap, var distinctCount: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = MutableIntIntMap()
        var distinct = 0
        for (i in xs.indices) {
            if (!present(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val prev = counts.getOrDefault(value, 0)
            counts.put(value, prev + 1)
            if (prev == 0) distinct++
        }
        state.refPayload[factorId] = State(counts, distinct)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return nvDegree(s.distinctCount, state.assignment.intValue(n)) > 0
    }

    /** Graded violation: distance between [n] and the actual distinct count — `|n − distinct|`
     *  for Eq, the one-sided shortfall/excess for AtLeast/AtMost — compressed. Gives CBLS a
     *  gradient toward the target count instead of a flat boolean. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as State
        val raw = nvDegree(s.distinctCount, state.assignment.intValue(n)).toLong()
        return compressViolation(raw, state.violationSoftCap)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val before = nvDegree(s.distinctCount, state.assignment.intValue(n))
        val newDistinct = simulateDistinct(state, s, intVar, newValue)
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        return compressViolation(nvDegree(newDistinct, newN).toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    /** Graded distance of a `distinct`-count against the target [n] value, per [mode]. */
    private fun nvDegree(distinct: Int, nVal: Int): Int = when (mode) {
        Mode.Eq -> if (nVal >= distinct) nVal - distinct else distinct - nVal
        Mode.AtLeast -> if (nVal > distinct) nVal - distinct else 0
        Mode.AtMost -> if (distinct > nVal) distinct - nVal else 0
    }

    private fun simulateDistinct(state: LocalSearchState, s: State, intVar: Int, newValue: Int): Int {
        // intVar's previous value affects xs only if it's one of the operands AND that
        // operand is present. If it's n (not an xs operand), distinct count unchanged.
        var occurrences = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrences++
        if (occurrences == 0) return s.distinctCount
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return s.distinctCount
        var distinct = s.distinctCount
        val oldCount = s.counts.getOrDefault(old, 0)
        // After removing `occurrences` of `old`: count' = oldCount - occurrences.
        if (oldCount - occurrences == 0) distinct--
        val newCount = s.counts.getOrDefault(newValue, 0)
        if (newCount == 0) distinct++
        return distinct
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val nBefore = if (intVar == n) oldValue else state.assignment.intValue(n)
        val beforeDeg = nvDegree(s.distinctCount, nBefore)
        var occurrences = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrences++
        if (occurrences > 0) {
            val oldCount = s.counts.getOrDefault(oldValue, 0)
            val after = oldCount - occurrences
            if (after == 0) {
                s.counts.remove(oldValue)
                s.distinctCount--
            } else {
                s.counts.put(oldValue, after)
            }
            val newCount = s.counts.getOrDefault(cur, 0)
            if (newCount == 0) s.distinctCount++
            s.counts.put(cur, newCount + occurrences)
        }
        val afterDeg = nvDegree(s.distinctCount, state.assignment.intValue(n))
        return compressViolation(afterDeg.toLong(), state.violationSoftCap) -
            compressViolation(beforeDeg.toLong(), state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val nVal = state.assignment.intValue(n)
        val before = nvDegree(s.distinctCount, nVal)
        // Simulate the flip's net effect on distinct.
        var distinct = s.distinctCount
        val touched = MutableIntIntMap() // value → delta to counts[value]
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = present(state, i)
            val value = state.assignment.intValue(xs[i])
            touched.addTo(value, if (wasP) -1 else 1)
        }
        touched.forEach { value, delta ->
            val cntBefore = s.counts.getOrDefault(value, 0)
            val cntAfter = cntBefore + delta
            if (cntBefore == 0 && cntAfter > 0) distinct++
            if (cntBefore > 0 && cntAfter == 0) distinct--
        }
        return compressViolation(nvDegree(distinct, nVal).toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val nVal = state.assignment.intValue(n)
        val beforeDeg = nvDegree(s.distinctCount, nVal)
        // The flip has already landed in [state.assignment]; recompute affected counts.
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = present(state, i)
            val value = state.assignment.intValue(xs[i])
            if (nowP) {
                val before = s.counts.getOrDefault(value, 0)
                if (before == 0) s.distinctCount++
                s.counts.put(value, before + 1)
            } else {
                if (!s.counts.containsKey(value)) error("nvalue: absent flip without prior count")
                val after = s.counts.getOrDefault(value, 0) - 1
                if (after == 0) {
                    s.counts.remove(value)
                    s.distinctCount--
                } else {
                    s.counts.put(value, after)
                }
            }
        }
        val afterDeg = nvDegree(s.distinctCount, state.assignment.intValue(n))
        return compressViolation(afterDeg.toLong(), state.violationSoftCap) -
            compressViolation(beforeDeg.toLong(), state.violationSoftCap)
    }

    /*
     * Bounds on `n`:
     *  - upper: number of distinct values in union of all xs domains.
     *  - lower: a greedy maximal set of present vars with pairwise-disjoint domains. Each such
     *    var is forced to a value no other selected var can take, so the distinct count is at
     *    least the set size — a Hall-style bound that subsumes the distinct-singleton count
     *    (singletons are size-1 disjoint domains) and strengthens [Mode.AtMost] / [Mode.Eq].
     */

    /** Distinct-count repair: snap `n` to current `distinctCount`, plus per-mode moves
     *  that nudge the distinct count in the right direction. To increase distinctCount,
     *  pick an `xs[i]` in a high-occurrence value class and shift it to a value currently
     *  uncovered (in its domain). To decrease, shift it to an already-covered value. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        val nv = state.assignment.intValue(n)
        // Snap n to the current distinct count when the mode would be satisfied by it.
        val nDom = state.problem.intDomains[n]
        if (s.distinctCount in nDom) sink.addChannelingIntSet(state, n, s.distinctCount)
        val needIncrease = when (mode) {
            Mode.Eq -> nv > s.distinctCount
            Mode.AtLeast -> true
            Mode.AtMost -> false
        }
        val needDecrease = when (mode) {
            Mode.Eq -> nv < s.distinctCount
            Mode.AtLeast -> false
            Mode.AtMost -> true
        }
        if (!needIncrease && !needDecrease) return
        if (needIncrease) {
            // Pick xs[i] in a duplicate value class (count > 1) and move it to an uncovered
            // value in its domain.
            for (i in xs.indices) {
                if (!present(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (s.counts.getOrDefault(cur, 0) <= 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Int? = null
                d.forEach { if (pick == null && it != cur && s.counts.getOrDefault(it, 0) == 0) pick = it }
                if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
            }
        }
        if (needDecrease) {
            // Pick xs[i] whose value is currently unique, move it to a covered value.
            for (i in xs.indices) {
                if (!present(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (s.counts.getOrDefault(cur, 0) > 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Int? = null
                d.forEach { if (pick == null && it != cur && s.counts.getOrDefault(it, 0) > 0) pick = it }
                if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: relabel one present `xs[i]` from value `v` to value
     *  `w` in a way that keeps the distinct-value count constant — either `v` survives and `w` is
     *  already present, or `v` disappears and `w` is freshly introduced (`vDies == wBorn`). The
     *  count, and hence the relation to `n`, is unchanged; only which value sits at `i` differs. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val i = state.rng.nextInt(xs.size)
            if (!present(state, i)) continue
            val v = state.assignment.intValue(xs[i])
            var occ = 0
            for (j in xs.indices) if (xs[j] == xs[i] && present(state, j)) occ++
            val cv = s.counts.getOrDefault(v, 0)
            val vDies = cv - occ == 0
            val d = state.problem.intDomains[xs[i]]
            var pick = -1
            var seen = 0
            d.forEach { w ->
                if (w != v) {
                    val wBorn = s.counts.getOrDefault(w, 0) == 0
                    if (vDies == wBorn) {
                        seen++
                        if (state.rng.nextInt(seen) == 0) pick = w
                    }
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, xs[i], pick)
            emitted++
        }
    }

    /** Feasible init: assign each present `xs` to its domain minimum, then set `n` to a value
     *  consistent with the realised distinct count under [mode]. Returns false — leaving the
     *  random assignment — when no such `n` is in domain or a frozen var blocks it. */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in xs.indices) {
            if (!present(state, i)) continue
            if (state.assumptions.isFrozenInt(xs[i])) continue
            state.assignment.setInt(xs[i], state.problem.intDomains[xs[i]].min)
        }
        val seen = IntHashSet()
        for (i in xs.indices) if (present(state, i)) seen.add(state.assignment.intValue(xs[i]))
        val distinct = seen.size
        val nDom = state.problem.intDomains[n]
        val target = when (mode) {
            Mode.Eq -> distinct
            Mode.AtLeast -> largestInDomainAtMost(nDom, distinct) ?: return false
            Mode.AtMost -> smallestInDomainAtLeast(nDom, distinct) ?: return false
        }
        if (state.assumptions.isFrozenInt(n)) return nvDegree(distinct, state.assignment.intValue(n)) == 0
        if (target !in nDom) return false
        state.assignment.setInt(n, target)
        return true
    }

    private fun largestInDomainAtMost(d: IntDomain, bound: Int): Int? {
        if (d.min > bound) return null
        var pick = -1
        d.forEach { if (it <= bound) pick = it }
        return if (pick < 0) null else pick
    }

    private fun smallestInDomainAtLeast(d: IntDomain, bound: Int): Int? {
        if (d.max < bound) return null
        var pick = -1
        d.forEach { if (pick < 0 && it >= bound) pick = it }
        return if (pick < 0) null else pick
    }

    private companion object {
        /** Cap on distinct-preserving relabel moves offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_MOVE_CAP: Int = 4

        /** Rejection-sampling attempts per requested move before giving up. */
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }

    /** Reason on conflict: bounds *and* interior holes of every participating var. The
     *  independent-set lower bound turns on whether domains are disjoint, which holey domains
     *  decide — so the reason must cite holes, not just bounds. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Delta fast path (#624, non-optional variant only): a fire that drains an empty dirty-variable
        // delta saw no domain change since the last bound recompute, so n is already at the tightest
        // bounds this constraint can derive. The first fire always recomputes. (The greedy independent-
        // set lower bound has no sound incremental form, so the recompute below stays full.)
        if (presents.isEmpty()) {
            val gate = (state.refPayload[factorId] as? CpGate) ?: run {
                val fresh = CpGate()
                state.refPayload[factorId] = fresh
                fresh
            }
            val dirty = state.drainIntEventDirtyVars(factorId)
            if (gate.started && dirty.isEmpty()) return true
            gate.started = true
        }
        // Upper bound: |∪ dom(xs[i])| for indices that aren't definitely absent.
        val unionValues = IntHashSet()
        for (i in xs.indices) {
            if (definitelyAbsent(i, state)) continue
            state.intDomains[xs[i]].forEach { unionValues.add(it) }
        }
        val maxDistinct = unionValues.size
        // Lower bound: greedy maximal independent set in the domain-overlap graph over
        // definitely-present entries. Process smallest domains first (more constrained, more
        // likely to stay disjoint); a var joins the set when its domain shares no value with
        // any already-selected var. Pairwise-disjoint domains force pairwise-distinct values.
        val present = IntArrayList(xs.size)
        for (i in xs.indices) if (definitelyPresent(i, state)) present.add(xs[i])
        present.sortByIntKey { state.intDomains[it].size }
        val covered = IntHashSet()
        var minDistinct = 0
        for (idx in 0 until present.size) {
            val d = state.intDomains[present[idx]]
            var disjoint = true
            d.forEach { if (covered.contains(it)) disjoint = false }
            if (disjoint) {
                minDistinct++
                d.forEach { covered.add(it) }
            }
        }
        val ant = collectHoleAndBoundAntecedents(state, xs)
        when (mode) {
            Mode.Eq -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            Mode.AtLeast -> {
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            Mode.AtMost -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
            }
        }
        return true
    }
}
