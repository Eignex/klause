package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

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
    val presents: IntArray = EmptyIntArray,
) : LocalSearchFactor {

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

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = xs + intArrayOf(n)

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)

    /** Maintains a per-value count over the assignment. `distinctCount` = number of values
     *  whose count is > 0. */
    private class State(val counts: HashMap<Int, Int>, var distinctCount: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var distinct = 0
        for (i in xs.indices) {
            if (!present(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val prev = counts[value] ?: 0
            counts[value] = prev + 1
            if (prev == 0) distinct++
        }
        state.refPayload[factorId] = State(counts, distinct)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        val nVal = state.assignment.intValue(n)
        return when (mode) {
            Mode.Eq -> nVal != s.distinctCount
            Mode.AtLeast -> nVal > s.distinctCount
            Mode.AtMost -> nVal < s.distinctCount
        }
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolatedInternal(s, state.assignment.intValue(n))
        val newDistinct = simulateDistinct(state, s, intVar, newValue)
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        val willViolate = isViolatedInternal(newDistinct, newN)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun isViolatedInternal(s: State, nVal: Int): Boolean = when (mode) {
        Mode.Eq -> nVal != s.distinctCount
        Mode.AtLeast -> nVal > s.distinctCount
        Mode.AtMost -> nVal < s.distinctCount
    }

    private fun isViolatedInternal(distinct: Int, nVal: Int): Boolean = when (mode) {
        Mode.Eq -> nVal != distinct
        Mode.AtLeast -> nVal > distinct
        Mode.AtMost -> nVal < distinct
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
        val oldCount = s.counts[old] ?: 0
        // After removing `occurrences` of `old`: count' = oldCount - occurrences.
        if (oldCount - occurrences == 0) distinct--
        val newCount = s.counts[newValue] ?: 0
        if (newCount == 0) distinct++
        return distinct
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val nVal = state.assignment.intValue(n)
        val wasViolated = isViolatedInternal(s, nVal)
        var occurrences = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrences++
        if (occurrences > 0) {
            val oldCount = s.counts[oldValue] ?: 0
            val after = oldCount - occurrences
            if (after == 0) {
                s.counts.remove(oldValue)
                s.distinctCount--
            } else {
                s.counts[oldValue] = after
            }
            val newCount = s.counts[cur] ?: 0
            if (newCount == 0) s.distinctCount++
            s.counts[cur] = newCount + occurrences
        }
        val nowViolated = isViolatedInternal(s, state.assignment.intValue(n))
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolatedInternal(s, state.assignment.intValue(n))
        // Simulate the flip's net effect on distinct.
        var distinct = s.distinctCount
        val touched = HashMap<Int, Int>() // value → delta to counts[value]
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = present(state, i)
            val value = state.assignment.intValue(xs[i])
            touched[value] = (touched[value] ?: 0) + if (wasP) -1 else 1
        }
        for ((value, delta) in touched) {
            val before = s.counts[value] ?: 0
            val after = before + delta
            if (before == 0 && after > 0) distinct++
            if (before > 0 && after == 0) distinct--
        }
        val willViolate = isViolatedInternal(distinct, state.assignment.intValue(n))
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolatedInternal(s, state.assignment.intValue(n))
        // The flip has already landed in [state.assignment]; recompute affected counts.
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = present(state, i)
            val value = state.assignment.intValue(xs[i])
            if (nowP) {
                val before = s.counts[value] ?: 0
                if (before == 0) s.distinctCount++
                s.counts[value] = before + 1
            } else {
                val before = s.counts[value] ?: error("nvalue: absent flip without prior count")
                val after = before - 1
                if (after == 0) {
                    s.counts.remove(value)
                    s.distinctCount--
                } else {
                    s.counts[value] = after
                }
            }
        }
        val nowViolated = isViolatedInternal(s, state.assignment.intValue(n))
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
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

            // nv > distinct → must raise distinct
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
                if ((s.counts[cur] ?: 0) <= 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Int? = null
                d.forEach { if (pick == null && it != cur && (s.counts[it] ?: 0) == 0) pick = it }
                if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
            }
        }
        if (needDecrease) {
            // Pick xs[i] whose value is currently unique, move it to a covered value.
            for (i in xs.indices) {
                if (!present(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if ((s.counts[cur] ?: 0) > 1) continue
                val d = state.problem.intDomains[xs[i]]
                var pick: Int? = null
                d.forEach { if (pick == null && it != cur && (s.counts[it] ?: 0) > 0) pick = it }
                if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
            }
        }
    }

    /** Reason on conflict: bounds *and* interior holes of every participating var. The
     *  independent-set lower bound turns on whether domains are disjoint, which holey domains
     *  decide — so the reason must cite holes, not just bounds. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Upper bound: |∪ dom(xs[i])| for indices that aren't definitely absent.
        val unionValues = HashSet<Int>()
        for (i in xs.indices) {
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            state.intDomains[xs[i]].forEach { unionValues.add(it) }
        }
        val maxDistinct = unionValues.size
        // Lower bound: greedy maximal independent set in the domain-overlap graph over
        // definitely-present entries. Process smallest domains first (more constrained, more
        // likely to stay disjoint); a var joins the set when its domain shares no value with
        // any already-selected var. Pairwise-disjoint domains force pairwise-distinct values.
        val present = ArrayList<Int>(xs.size)
        for (i in xs.indices) if (OptPresence.isDefinitelyPresent(presents, i, state)) present.add(xs[i])
        present.sortBy { state.intDomains[it].size }
        val covered = HashSet<Int>()
        var minDistinct = 0
        for (xi in present) {
            val d = state.intDomains[xi]
            var disjoint = true
            d.forEach { if (it in covered) disjoint = false }
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
