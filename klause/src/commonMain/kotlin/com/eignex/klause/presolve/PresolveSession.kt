package com.eignex.klause.presolve

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList

/**
 * A pass's incremental change to the working problem: factors to drop (by stable id), factors to
 * add, and the pass's directly-derived domain tightenings.
 *
 * [domains] carries the pass's own narrowings (a dual-fixed bound, an affine substitution's range) as
 * a full int-domain array, or `null` when the pass leaves domains alone. Tightenings that follow from
 * the kept and added factors are re-derived by re-propagation and need not appear here — listing only
 * what a pass *cannot* have the propagator rediscover keeps the delta minimal and the fixpoint intact.
 */
internal class PresolveDelta(
    val droppedIds: IntArray = EmptyIntArray,
    val addedFactors: List<Factor> = emptyList(),
    val domains: Array<IntDomain>? = null,
) {
    val isEmpty: Boolean
        get() = droppedIds.isEmpty() && addedFactors.isEmpty() && domains == null
}

/**
 * Owns the persistent state of an incremental presolve run so the round engine never rebuilds a
 * [Problem] per firing pass. Holds the working factor set as a stable-id, append-only list (a
 * tombstoned slot becomes `null` and its id is never reused) and one persistent [PropagationState]
 * sized to the original variable count — valid for the whole run because presolve never renumbers
 * variables.
 *
 * A pass emits a [PresolveDelta]; [apply] drops/adds factors and pushes the pass's domain narrowings
 * into the live state, then re-propagates incrementally from just that delta via the existing
 * dirty-variable/watcher machinery. Because the propagators are monotone the greatest fixpoint is
 * unique, so this reaches the same tightened domains a from-scratch bake over the final factor set
 * would — no per-pass `computeBaked`. [materialize] builds the heavyweight solver [Problem] once at
 * the end, with the single renumber/remap.
 */
internal class PresolveSession(private val base: Problem) {

    // Working factor set indexed by stable id: `[0, base.factors.size)` are the originals, appended
    // ids are pass-added factors. `null` marks a tombstoned (dropped) id; ids are never renumbered.
    private val factors: ArrayList<Factor?> = ArrayList<Factor?>(base.factors.size).apply { addAll(base.factors) }

    private val state = PropagationState(base, Assumptions.None, incremental = true)

    /** Set once the base bake or a delta re-propagation derives a root contradiction. */
    var infeasible: Boolean = false
        private set

    init {
        // Establish the base fixpoint on the persistent state. The base [Problem] already folded its
        // root deductions into [Problem.intDomains] (copied into the state), so this settles at that
        // same greatest fixpoint; it primes the watcher/dirty machinery for incremental re-propagation.
        if (state.runToFixpoint(allFactors = true) != null) infeasible = true
    }

    /** Live (non-tombstoned) factors in stable-id order — the current working constraint set. */
    fun liveFactors(): List<Factor> = factors.filterNotNull()

    /** The current tightened domain of int var [v] on the persistent state. */
    fun intDomainOf(v: Int): IntDomain = state.intDomains[v]

    /** Current value of bool var [v] on the persistent state, or `null` if still free. */
    fun boolValueOf(v: Int): Boolean? = state.boolValues[v]

    /**
     * Apply [delta] incrementally: tombstone dropped ids, append added factors, push the pass's domain
     * narrowings, then re-propagate from just the delta. Returns `false` iff this proved infeasibility
     * (a conflict on a pushed narrowing or during re-propagation); the session latches [infeasible].
     */
    fun apply(delta: PresolveDelta): Boolean {
        if (infeasible) return false
        for (id in delta.droppedIds) {
            state.tombstoneFactor(id)
            factors[id] = null
        }
        val addedIds = IntArrayList(delta.addedFactors.size)
        for (f in delta.addedFactors) {
            addedIds.add(state.addMidlifeFactor(f)) // fid == factors.size at this point
            factors.add(f)
        }
        var ok = pushDomainNarrowings(delta.domains)
        if (ok && state.runToFixpoint(allFactors = false, initialFactors = addedIds.toIntArray()) != null) ok = false
        if (!ok) infeasible = true
        return ok
    }

    /** Push the per-variable diff of [domains] against the live state into the state's mutation API so
     *  dependent factors re-wake. `null` (no domain change) is a no-op. Returns false on a conflict. */
    private fun pushDomainNarrowings(domains: Array<IntDomain>?): Boolean {
        if (domains == null) return true
        for (v in domains.indices) {
            val target = domains[v]
            val cur = state.intDomains[v]
            if (target.min == cur.min && target.max == cur.max && target.size == cur.size) continue
            if (!state.tightenIntMin(v, target.min)) return false
            if (!state.tightenIntMax(v, target.max)) return false
            if (!pushInteriorHoles(v, target)) return false
        }
        return true
    }

    /** Remove from int var [v] the interior values the pass carved out of [target] — those still present
     *  on the state but absent from the target (rare; bound tightenings dominate). Returns false on a
     *  conflict, and skips the value scan entirely when the state domain already has no extra values. */
    private fun pushInteriorHoles(v: Int, target: IntDomain): Boolean {
        if (target.size == state.intDomains[v].size) return true
        for (value in target.min..target.max) {
            if (value in target || value !in state.intDomains[v]) continue
            if (!state.excludeIntValue(v, value)) return false
        }
        return true
    }

    /** Materialize the final solver [Problem] once: the live factors and the state's tightened int
     *  domains. Its own bake re-derives the bool pins and any residual tightenings from the factors. */
    fun materialize(): Problem {
        val domains = Array(base.numIntVars) { state.intDomains[it] }
        return PresolveShared.rebuildProblem(base, liveFactors(), domains)
    }
}
