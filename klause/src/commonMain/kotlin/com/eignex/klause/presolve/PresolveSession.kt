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
internal class PresolveSession(private val base: Problem, private val bakeConfig: BakeConfig = BakeConfig.NONE) {

    // Working factor set indexed by stable id: `[0, base.factors.size)` are the originals, appended
    // ids are pass-added factors. `null` marks a tombstoned (dropped) id; ids are never renumbered.
    // Refilled in place on a [reseed] (a domain-widening pass the incremental path can't express).
    private val factors: ArrayList<Factor?> = ArrayList<Factor?>(base.factors.size).apply { addAll(base.factors) }

    // The problem the current [state] is baked over: [base] initially, a widening pass's output after a
    // [reseed]. Its factor count is the stable-id boundary between base and mid-life factors.
    private var stateProblem: Problem = base

    private var state = PropagationState(base, Assumptions.None, incremental = true)

    /** Set once the base bake or a delta re-propagation derives a root contradiction. */
    var infeasible: Boolean = false
        private set

    // Domains as of the last point the problem was still feasible. On infeasibility the reported /
    // materialized domains are these, not the partially-tightened live ones — mirroring the fresh path,
    // where `foldIntoDomains` skips on an Unsat bake and the reported span stays at the pre-conflict
    // domains. Snapshotted at each mutation entry (before its narrowings) and initialised to the base.
    private var lastFeasibleDomains: Array<IntDomain> = Array(base.numIntVars) { base.intDomains[it] }

    init {
        // Establish the base fixpoint on the persistent state. The base [Problem] already folded its
        // root deductions into [Problem.intDomains] (copied into the state), so this settles at that
        // same greatest fixpoint; it primes the watcher/dirty machinery for incremental re-propagation.
        if (state.runToFixpoint(allFactors = true) != null) infeasible = true
    }

    /** Live (non-tombstoned) factors in stable-id order — the current working constraint set. */
    fun liveFactors(): List<Factor> = factors.filterNotNull()

    /** Number of live (non-tombstoned) factors. */
    val liveFactorCount: Int get() = factors.count { it != null }

    /** Cheap problem-complexity measure mirroring [Presolver]'s fresh-path `complexity`: live constraint
     *  count plus total int-domain span. Drives the round engine's diminishing-returns abort. */
    fun complexity(): Long {
        var c = liveFactorCount.toLong()
        for (v in 0 until base.numIntVars) {
            val d = state.intDomains[v]
            c += d.max.toLong() - d.min.toLong()
        }
        return c
    }

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
        if (!infeasible) snapshotFeasibleDomains()
        for (id in delta.droppedIds) {
            state.tombstoneFactor(id)
            factors[id] = null
        }
        val addedIds = IntArrayList(delta.addedFactors.size)
        for (f in delta.addedFactors) {
            addedIds.add(state.addMidlifeFactor(f)) // fid == factors.size at this point
            factors.add(f)
        }
        // Once infeasible, the factor changes above are still recorded (the materialized problem's bake
        // resurfaces the infeasibility) but the conflicted state must not be re-propagated. Thread the
        // pass's output domains forward as the reported domains so a later pass sees this pass's
        // narrowings — mirroring the fresh path, which carries each pass's fold-skipped domains to the
        // next, and keeping domain-pinning passes (dual fixing) converging on an infeasible problem.
        if (infeasible) {
            delta.domains?.let { d -> lastFeasibleDomains = Array(base.numIntVars) { d[it] } }
            return false
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

    /**
     * A cheap [Problem] for a pass to read: the live factors and the state's current folded domains,
     * built in [Problem.preFolded] mode so it never bakes or inverts occurrences. The passes read only
     * `factors` and `intDomains`, so nothing deferred is ever forced — construction is O(live factors).
     */
    fun passInput(): Problem = Problem(
        numBoolVars = base.numBoolVars,
        numIntVars = base.numIntVars,
        // Once infeasible, expose the clean pre-conflict domains — not the partially-tightened live ones
        // a conflicted re-propagation left — so a later pass sees what the fresh path (fold skipped on an
        // Unsat bake) would and fires identically.
        intDomains = if (infeasible) lastFeasibleDomains else Array(base.numIntVars) { state.intDomains[it] },
        factors = liveFactors(),
        preFolded = true,
    )

    /**
     * Apply a pass's returned [Problem] to the session incrementally. The pass produced a new factor
     * list + domains; the delta against the working set is recovered by identity ([Factor] uses
     * reference equality), then applied via [apply]. A pass that *widens* a domain (dup-columns'
     * aggregate variable) can't be expressed by monotone re-propagation, so it triggers a [reseed] — a
     * one-off from-scratch bake over the pass's output, which is exactly the prior per-pass behaviour and
     * therefore byte-identical. Returns `false` iff infeasibility was proved.
     */
    fun applyResult(result: Problem): Boolean {
        // A domain widen needs a from-scratch reseed — but only while still feasible; on an already
        // infeasible problem the widen is moot and the factor changes are tracked through [apply] below.
        if (!infeasible && widensAnyDomain(result.intDomains)) return reseed(result)
        val liveById = HashMap<Factor, Int>(factors.size)
        for (id in factors.indices) factors[id]?.let { liveById[it] = id }
        val kept = HashSet<Int>(result.factors.size)
        val added = ArrayList<Factor>()
        for (f in result.factors) {
            val id = liveById[f]
            if (id != null) kept.add(id) else added.add(f)
        }
        val dropped = IntArrayList()
        for (id in factors.indices) if (factors[id] != null && id !in kept) dropped.add(id)
        return apply(PresolveDelta(dropped.toIntArray(), added, result.intDomains))
    }

    /** Whether [domains] widens any variable past the live state — a value the state currently excludes
     *  becomes allowed. Monotone re-propagation only narrows, so a widen needs a [reseed] instead. */
    private fun widensAnyDomain(domains: Array<IntDomain>): Boolean {
        for (v in 0 until base.numIntVars) {
            val cur = state.intDomains[v]
            val target = domains[v]
            if (target.min < cur.min || target.max > cur.max || target.size > cur.size) return true
        }
        return false
    }

    /** Rebuild the persistent state from scratch over [result] (fresh eager bake). Used only when a pass
     *  widens a domain; correct because it reproduces the exact from-scratch problem for that transition. */
    private fun reseed(result: Problem): Boolean {
        val eager = PresolveShared.rebuildProblem(
            base,
            result.factors.toList(),
            Array(base.numIntVars) { result.intDomains[it] },
            bakeConfig,
        )
        stateProblem = eager
        factors.clear()
        factors.addAll(eager.factors)
        state = PropagationState(eager, Assumptions.None, incremental = true)
        if (state.runToFixpoint(allFactors = true) != null) {
            infeasible = true
            return false
        }
        return true
    }

    /** Capture the live domains as the last-feasible snapshot, taken before a mutation's narrowings so
     *  that if the mutation proves infeasible the reported domains are the pre-conflict ones. */
    private fun snapshotFeasibleDomains() {
        lastFeasibleDomains = Array(base.numIntVars) { state.intDomains[it] }
    }

    /** Materialize the final solver [Problem] once: the live factors and the tightened int domains. On
     *  infeasibility the pre-conflict [lastFeasibleDomains] are used (the fresh path likewise skips
     *  folding an Unsat bake); otherwise the state's fully-folded domains. Its own bake re-derives the
     *  bool pins and any residual tightenings, and surfaces the infeasibility as an Unsat [Problem.baked]. */
    fun materialize(): Problem {
        val domains = if (infeasible) lastFeasibleDomains else Array(base.numIntVars) { state.intDomains[it] }
        return PresolveShared.rebuildProblem(stateProblem, liveFactors(), domains, bakeConfig)
    }
}
