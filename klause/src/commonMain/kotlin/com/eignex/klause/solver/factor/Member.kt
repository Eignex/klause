package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * `member_int(xs, y)` — `y` equals at least one of the `xs[i]`. The dual of
 * disjunction-of-equalities: `(y = xs[0]) ∨ (y = xs[1]) ∨ … ∨ (y = xs[n-1])`.
 *
 * Propagation:
 *  - **union-hull on `y`**: `y` must equal some candidate, so every `y`-value that no `xs[i]`
 *    domain contains is pruned (an empty result fails).
 *  - **unique-support channeling**: once `y` is pinned to `v`, if exactly one candidate can
 *    still take `v` it is forced to `v` (it is the only possible witness); zero candidates fail.
 */
class Member(
    /** The candidate variable ids. */
    val xs: IntArray,
    /** Variable id required to equal one of [xs]. */
    val y: Int,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "member: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(y)

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val yv = state.assignment.intValue(y)
        for (x in xs) if (state.assignment.intValue(x) == yv) return false
        return true
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val yv = if (intVar == y) newValue else state.assignment.intValue(y)
        var matched = false
        for (x in xs) {
            val xv = if (x == intVar) newValue else state.assignment.intValue(x)
            if (xv == yv) {
                matched = true
                break
            }
        }
        val willViolate = !matched
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Propose either snapping `y` to one of the `xs[i]` values (if the domain allows),
     *  or snapping an `xs[i]` to `y`'s current value. Both directions resolve the
     *  violation, and the strategy can pick the cheaper one. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val yv = state.assignment.intValue(y)
        val dy = state.problem.intDomains[y]
        // Direction 1: snap y to an existing xs[i] value.
        val seen = IntHashSet()
        for (x in xs) {
            val xv = state.assignment.intValue(x)
            if (seen.add(xv) && xv != yv && xv in dy) {
                sink.addChannelingIntSet(state, y, xv)
            }
        }
        // Direction 2: snap some xs[i] to yv if its domain allows.
        for (x in xs) {
            val xv = state.assignment.intValue(x)
            if (xv != yv && yv in state.problem.intDomains[x]) {
                sink.addChannelingIntSet(state, x, yv)
            }
        }
    }

    /** Conflict reason, sharpened to the singleton-y witness captured by [propagate]; falls
     *  back to the hole-aware constraint-wide reason otherwise. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? IntArray) ?: collectHoleAndBoundAntecedents(state, intVars)

    /** Append the one literal proving value [v] is absent from `dom(x)` — the tightened
     *  bound that steps past it, or the hole atom when [v] sits inside the bounds. Skips a
     *  bound still at its original value (a structural/global fact with no trail literal).
     *  Uses the same atoms as [collectHoleAndBoundAntecedents], so soundness/level handling
     *  matches. Caller guarantees `v !in dom(x)`. */
    private fun addExcludeLit(state: PropagationState, x: Int, v: Int, out: IntArrayList, seen: IntHashSet) {
        val d = state.intDomains[x]
        val orig = state.problem.intDomains[x]
        val lit = when {
            d.min > v -> if (d.min > orig.min) Lit.make(state.atomVarGe(x, d.min), false) else return
            d.max < v -> if (d.max < orig.max) Lit.make(state.atomVarLe(x, d.max), false) else return
            else -> state.atomLitNe(x, v) // v inside [min,max] but removed → a hole
        }
        if (seen.add(lit)) out.add(lit)
    }

    /** Append `y`'s pin literals (the tightened bounds collapsing it to a singleton). */
    private fun addPinLits(state: PropagationState, out: IntArrayList, seen: IntHashSet) {
        val d = state.intDomains[y]
        val orig = state.problem.intDomains[y]
        if (d.min > orig.min) {
            val l = Lit.make(state.atomVarGe(y, d.min), false)
            if (seen.add(l)) out.add(l)
        }
        if (d.max < orig.max) {
            val l = Lit.make(state.atomVarLe(y, d.max), false)
            if (seen.add(l)) out.add(l)
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.refPayload[factorId] = null // stale-guard; set at the singleton-y failure point below.
        // Singleton-y check: if y is singleton and no xs[i]'s domain contains y's value, fail.
        val dy = state.intDomains[y]
        if (dy.min == dy.max) {
            val yv = dy.min
            var anyContains = false
            for (x in xs) {
                if (yv in state.intDomains[x]) {
                    anyContains = true
                    break
                }
            }
            if (!anyContains) {
                // Sharp reason: y is pinned to yv and every candidate excludes yv. Cite y's
                // pin plus, per candidate, only the literal that excludes yv — not its whole
                // domain. (Every candidate participates, so the var set itself is minimal.)
                val out = IntArrayList()
                val seen = IntHashSet()
                addPinLits(state, out, seen)
                for (x in xs) addExcludeLit(state, x, yv, out, seen)
                state.refPayload[factorId] = if (out.size == 0) null else out.toIntArray()
                return false
            }
        }
        // Union-hull on y: y = some xs[i], so every y-value that no candidate domain contains
        // is unsupported.
        val toRemove = IntArrayList()
        state.intDomains[y].forEach { v ->
            var supported = false
            for (x in xs) {
                if (v in state.intDomains[x]) {
                    supported = true
                    break
                }
            }
            if (!supported) toRemove.add(v)
        }
        if (toRemove.size > 0) {
            val ant = collectHoleAndBoundAntecedents(state, xs)
            for (k in 0 until toRemove.size) if (!state.excludeIntValue(y, toRemove[k], ant)) return false
        }
        // Unique-support channeling: if y is now pinned to yv and exactly one candidate can
        // take yv, that candidate is the only possible witness and must equal yv.
        val dyNow = state.intDomains[y]
        if (dyNow.min == dyNow.max) {
            val yv = dyNow.min
            var witness = -1
            var count = 0
            for (x in xs) {
                if (yv in state.intDomains[x]) {
                    count++
                    witness = x
                }
            }
            if (count == 0) return false // no candidate left (defensive; hull prune should catch)
            if (count == 1) {
                val d = state.intDomains[witness]
                if (!(d.min == d.max && d.min == yv)) {
                    val drop = IntArrayList()
                    d.forEach { if (it != yv) drop.add(it) }
                    val ant = collectHoleAndBoundAntecedents(state, intVars)
                    for (k in 0 until drop.size) if (!state.excludeIntValue(witness, drop[k], ant)) return false
                }
            }
        }
        return true
    }
}
