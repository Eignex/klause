package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `count_⟨op⟩(xs, v, n)` — `n = #{i : xs[i] ⟨op⟩ v}`. Single factor covering all six
 * MiniZinc count variants via [op]:
 *
 *  - [Op.Eq]:  `xs[i] = v`
 *  - [Op.Ne]:  `xs[i] ≠ v`
 *  - [Op.Le]:  `xs[i] ≤ v`
 *  - [Op.Lt]:  `xs[i] < v`
 *  - [Op.Ge]:  `xs[i] ≥ v`
 *  - [Op.Gt]:  `xs[i] > v`
 *
 * Variants where `v` is a variable rather than a constant land via the existing decomposition
 * path (channel `xs[i] − v ⟨op⟩ 0` through reified linears); this factor takes a *constant*
 * target — the common case in MiniZinc-emitted FlatZinc.
 */
class Count(
    val xs: IntArray,
    val v: Int,
    val op: Op,
    val n: Int,
    /** Per-index presence literals; empty for the non-opt fast path. See [OptPresence]. */
    val presents: IntArray = EmptyIntArray,
) : LocalSearchFactor {

    enum class Op { Eq, Ne, Le, Lt, Ge, Gt }

    init {
        require(xs.isNotEmpty()) { "count: empty xs" }
        require(presents.isEmpty() || presents.size == xs.size) {
            "count: presents must be empty or match xs arity (got ${presents.size} vs ${xs.size})"
        }
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = xs + intArrayOf(n)

    private fun present(state: LocalSearchState, idx: Int): Boolean =
        OptPresence.isPresentInAssignment(presents, idx, state)

    /** Cached count of xs[i] satisfying the predicate under the current assignment. */
    private class State(var count: Int)

    private fun matches(value: Int): Boolean = when (op) {
        Op.Eq -> value == v
        Op.Ne -> value != v
        Op.Le -> value <= v
        Op.Lt -> value < v
        Op.Ge -> value >= v
        Op.Gt -> value > v
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var c = 0
        for (i in xs.indices) if (present(state, i) && matches(state.assignment.intValue(xs[i]))) c++
        state.refPayload[factorId] = State(c)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return state.assignment.intValue(n) != s.count
    }

    /** Graded violation: the constraint is `n == #matches`, so the degree is `|n − count|` —
     *  how far the count variable is off-target. Gives CBLS a gradient rewarding moves that
     *  nudge the match count toward `n` (or `n` toward the count). */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as State
        return degree(state.assignment.intValue(n), s.count)
    }

    private fun degree(nValue: Int, count: Int): Int {
        val d = nValue.toLong() - count
        return compressViolation(if (d < 0) -d else d)
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        var deltaCount = 0
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            if (!present(state, i)) continue
            val old = state.assignment.intValue(intVar)
            val wasMatch = matches(old)
            val willMatch = matches(newValue)
            if (wasMatch && !willMatch) deltaCount--
            if (!wasMatch && willMatch) deltaCount++
        }
        val newCount = s.count + deltaCount
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        return degree(newN, newCount) - degree(state.assignment.intValue(n), s.count)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val oldN = if (intVar == n) oldValue else state.assignment.intValue(n)
        val oldCount = s.count
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            if (!present(state, i)) continue
            val wasMatch = matches(oldValue)
            val nowMatch = matches(cur)
            if (wasMatch && !nowMatch) s.count--
            if (!wasMatch && nowMatch) s.count++
        }
        return degree(state.assignment.intValue(n), s.count) - degree(oldN, oldCount)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        var deltaCount = 0
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasPresent = present(state, i)
            val willBePresent = !wasPresent // flipping the bool inverts the literal's truth
            val matchesValue = matches(state.assignment.intValue(xs[i]))
            if (!matchesValue) continue
            if (wasPresent && !willBePresent) deltaCount--
            if (!wasPresent && willBePresent) deltaCount++
        }
        val newCount = s.count + deltaCount
        val nv = state.assignment.intValue(n)
        return degree(nv, newCount) - degree(nv, s.count)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val nv = state.assignment.intValue(n)
        val oldCount = s.count
        // The flip has already been applied to `state.assignment`; recompute contributions
        // for every entry whose presence literal references [boolVar].
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowPresent = present(state, i)
            val matchesValue = matches(state.assignment.intValue(xs[i]))
            if (!matchesValue) continue
            if (nowPresent) s.count++ else s.count--
        }
        return degree(nv, s.count) - degree(nv, oldCount)
    }

    /**
     * `n` bounded by the count of definite-matchers (lower) and possible-matchers (upper).
     * A var is a definite-matcher when its *entire* domain satisfies the predicate; a
     * possible-matcher when *some* of its domain does.
     */
    /** Bound-only conflict reason: cite bound atoms of every participating var. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var definite = 0
        var possible = 0
        for (i in xs.indices) {
            // A definitely-absent entry contributes nothing to either bound.
            if (OptPresence.isDefinitelyAbsent(presents, i, state)) continue
            val d = state.intDomains[xs[i]]
            val all = domainAllMatches(d)
            val any = domainAnyMatches(d)
            // Definite (lower bound) requires the entry to be present too — unpinned-presence
            // entries can still go absent and stop matching.
            if (all && OptPresence.isDefinitelyPresent(presents, i, state)) definite++
            if (any) possible++
        }
        val ant = state.composeIntVarAtomAntecedents(xs)
        if (!state.tightenIntMin(n, definite, ant)) return false
        if (!state.tightenIntMax(n, possible, ant)) return false
        return true
    }

    private fun domainAllMatches(d: com.eignex.klause.solver.IntDomain): Boolean = when (op) {
        Op.Eq -> d.min == d.max && d.min == v
        Op.Ne -> d.max < v || d.min > v
        Op.Le -> d.max <= v
        Op.Lt -> d.max < v
        Op.Ge -> d.min >= v
        Op.Gt -> d.min > v
    }

    /** Three concurrent repair directions for a violated count:
     *  (a) snap `n` to the current count (the cheapest direction when `n` is unconstrained),
     *  (b) flip matching xs[i] off the predicate (when count > n),
     *  (c) flip non-matching xs[i] onto the predicate (when count < n).
     *  For (b)/(c) we propose the closest-in-domain value that flips the match outcome. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        val cur = s.count
        val nv = state.assignment.intValue(n)
        // (a) Snap n to the current count.
        if (cur in state.problem.intDomains[n]) sink.addChannelingIntSet(state, n, cur)
        if (nv == cur) return // already satisfied (shouldn't happen given violation)
        val needIncrease = cur < nv // need more matches
        val needDecrease = cur > nv
        for (i in xs.indices) {
            if (!present(state, i)) continue
            val xi = xs[i]
            val d = state.problem.intDomains[xi]
            val curX = state.assignment.intValue(xi)
            val isMatch = matches(curX)
            if (isMatch && needDecrease) {
                // Find any in-domain value that doesn't match (op-specific).
                val target = pickNonMatching(d, curX) ?: continue
                if (target != curX) sink.addChannelingIntSet(state, xi, target)
            } else if (!isMatch && needIncrease) {
                val target = pickMatching(d, curX) ?: continue
                if (target != curX) sink.addChannelingIntSet(state, xi, target)
            }
        }
    }

    /** Pick an in-domain value that matches the predicate; returns null if impossible. */
    private fun pickMatching(d: com.eignex.klause.solver.IntDomain, avoid: Int): Int? = when (op) {
        Op.Eq -> if (v in d && v != avoid) v else null
        Op.Ne -> {
            var pick: Int? = null
            d.forEach { if (it != v && it != avoid && pick == null) pick = it }
            pick
        }
        Op.Le -> if (d.min <= v) d.min.takeIf { it != avoid } ?: d.min else null
        Op.Lt -> if (d.min < v) d.min.takeIf { it != avoid } ?: d.min else null
        Op.Ge -> if (d.max >= v) d.max.takeIf { it != avoid } ?: d.max else null
        Op.Gt -> if (d.max > v) d.max.takeIf { it != avoid } ?: d.max else null
    }

    /** Pick an in-domain value that does NOT match the predicate; returns null if impossible. */
    private fun pickNonMatching(d: com.eignex.klause.solver.IntDomain, avoid: Int): Int? = when (op) {
        Op.Eq -> {
            var pick: Int? = null
            d.forEach { if (it != v && it != avoid && pick == null) pick = it }
            pick
        }
        Op.Ne -> if (v in d && v != avoid) v else null
        Op.Le -> if (d.max > v) d.max.takeIf { it != avoid } ?: d.max else null
        Op.Lt -> if (d.max >= v) d.max.takeIf { it != avoid } ?: d.max else null
        Op.Ge -> if (d.min < v) d.min.takeIf { it != avoid } ?: d.min else null
        Op.Gt -> if (d.min <= v) d.min.takeIf { it != avoid } ?: d.min else null
    }

    private fun domainAnyMatches(d: com.eignex.klause.solver.IntDomain): Boolean = when (op) {
        Op.Eq -> v in d
        Op.Ne -> !(d.min == d.max && d.min == v)
        Op.Le -> d.min <= v
        Op.Lt -> d.min < v
        Op.Ge -> d.max >= v
        Op.Gt -> d.max > v
    }
}
