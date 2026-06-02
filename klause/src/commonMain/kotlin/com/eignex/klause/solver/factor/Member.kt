package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/**
 * `member_int(xs, y)` — `y` equals at least one of the `xs[i]`. The dual of
 * disjunction-of-equalities: `(y = xs[0]) ∨ (y = xs[1]) ∨ … ∨ (y = xs[n-1])`.
 *
 * Propagation: when every `xs[i]`'s domain is disjoint from `y`'s domain, fail; when
 * `xs` has length 1, force `y = xs[0]`.
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
        val seen = HashSet<Int>()
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

    /** Bound-style sharpened antecedents captured at a singleton-y conflict: `y`'s pin plus,
     *  per candidate, only the single literal proving `y`'s value is excluded from it —
     *  strictly sharper than every var's whole domain. `null` ⇒ no such conflict captured
     *  this run (the `allSingleton` exclusion path genuinely needs all candidates), so fall
     *  back to the constraint-wide reason. */
    private var conflictLits: IntArray? = null

    /** Conflict reason, sharpened to the singleton-y witness captured by [propagate]; falls
     *  back to the hole-aware constraint-wide reason otherwise. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        conflictLits ?: collectHoleAndBoundAntecedents(state, intVars)

    /** Append the one literal proving value [v] is absent from `dom(x)` — the tightened
     *  bound that steps past it, or the hole atom when [v] sits inside the bounds. Skips a
     *  bound still at its original value (a structural/global fact with no trail literal).
     *  Uses the same atoms as [collectHoleAndBoundAntecedents], so soundness/level handling
     *  matches. Caller guarantees `v !in dom(x)`. */
    private fun addExcludeLit(state: PropagationState, x: Int, v: Int, out: IntArrayList, seen: HashSet<Int>) {
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
    private fun addPinLits(state: PropagationState, out: IntArrayList, seen: HashSet<Int>) {
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
        conflictLits = null // stale-guard; set at the singleton-y failure point below.
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
                val seen = HashSet<Int>()
                addPinLits(state, out, seen)
                for (x in xs) addExcludeLit(state, x, yv, out, seen)
                conflictLits = if (out.size == 0) null else out.toIntArray()
                return false
            }
        }
        // Singleton-xs[i]: if every xs[i] is singleton, y must equal one of them.
        var allSingleton = true
        for (x in xs) {
            if (state.intDomains[x].min != state.intDomains[x].max) {
                allSingleton = false
                break
            }
        }
        if (allSingleton) {
            val values = HashSet<Int>()
            for (x in xs) values.add(state.intDomains[x].min)
            // Restrict y's domain to the value set.
            val toRemove = IntArrayList()
            dy.forEach { if (it !in values) toRemove.add(it) }
            val ant = state.composeIntVarAtomAntecedents(xs)
            for (k in 0 until toRemove.size) if (!state.excludeIntValue(y, toRemove[k], ant)) return false
        }
        return true
    }
}
