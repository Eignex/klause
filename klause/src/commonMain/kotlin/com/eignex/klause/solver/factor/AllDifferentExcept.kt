package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Generalised `alldifferent_except(xs, except)` — `xs[i] != xs[j]` for every pair `i < j`
 * unless one of the two values is in [except]. The classic [AllDifferentExceptZero] is the
 * `except = {0}` specialisation; this factor uses a HashSet membership check so propagation
 * over arbitrary excluded-value sets remains O(N · |except|) per call.
 *
 * Propagation is the same singleton-take filter as the zero-only variant: any var pinned to
 * a non-excluded value `v` removes `v` from every other var's domain. Régin-style stronger
 * propagation is tracked as a follow-up.
 */
class AllDifferentExcept(
    val xs: IntArray,
    except: IntArray,
) : LocalSearchFactor {

    val except: IntArray = except.toSortedSet().toIntArray()
    private val exceptSet: Set<Int> = this.except.toHashSet()

    init {
        require(xs.size >= 2) { "AllDifferentExcept needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Per-value count among non-excluded values. `violatedPairs` is the number of (i, j) with
     *  i < j and xs[i] = xs[j] ∉ except. */
    private class State(val counts: HashMap<Int, Int>, var violatedPairs: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var bad = 0
        for (v in xs) {
            val value = state.assignment.intValue(v)
            if (value in exceptSet) continue
            val prev = counts[value] ?: 0
            counts[value] = prev + 1
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
            val cnt = s.counts[old] ?: 0
            val after = cnt - occurrences
            bad -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
        }
        if (newValue !in exceptSet) {
            val cnt = s.counts[newValue] ?: 0
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
            val cnt = s.counts[oldValue] ?: 0
            val after = cnt - occurrences
            s.violatedPairs -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
            if (after <= 0) s.counts.remove(oldValue) else s.counts[oldValue] = after
        }
        if (cur !in exceptSet) {
            val cnt = s.counts[cur] ?: 0
            val after = cnt + occurrences
            s.violatedPairs += pairsAt(after) - pairsAt(cnt)
            s.counts[cur] = after
        }
        val nowViolated = s.violatedPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun pairsAt(k: Int): Int = if (k <= 1) 0 else k * (k - 1) / 2

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val taken = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (d.min in exceptSet) continue
            if (!taken.add(d.min)) return false
        }
        if (taken.isNotEmpty()) {
            val ant = state.composeIntVarAtomAntecedents(xs)
            for (v in xs) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                for (t in taken) {
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(v, t, ant)) return false
                }
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.violatedPairs == 0) return
        var target: Int = Int.MIN_VALUE
        for ((value, count) in s.counts) {
            if (count >= 2) { target = value; break }
        }
        if (target == Int.MIN_VALUE) return
        for (v in xs) {
            if (state.assignment.intValue(v) != target) continue
            val d = state.problem.intDomains[v]
            // Try setting to any excluded sentinel that's in the domain.
            for (e in except) {
                if (e in d) { sink.addIntSet(v, e); break }
            }
            if (target > d.min && (target - 1) in d) sink.addIntSet(v, target - 1)
            if (target < d.max && (target + 1) in d) sink.addIntSet(v, target + 1)
            return
        }
    }
}
