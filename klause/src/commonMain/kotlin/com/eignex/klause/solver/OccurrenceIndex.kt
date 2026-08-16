package com.eignex.klause.solver

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

/**
 * Variable → factor occurrence lists for a [Problem], inverted once at construction and split by
 * engine (CP vs local search) and by wakeup mode (occurrence-driven vs watcher-driven). A `flip` or
 * `setInt` on a variable walks only the factors mentioning it, so every engine reads its own list
 * here instead of scanning the whole factor set.
 *
 * The CP lists ([boolOccurrences] / [intOccurrences]) and the watcher-exclusion lists build eagerly;
 * the local-search lists ([lsBoolOccurrences] / [lsIntOccurrences]) are lazy and force [invariants]
 * only on first access, so a presolve- or CP-only [Problem] never allocates the LS-side inversion.
 */
class OccurrenceIndex(
    private val numBoolVars: Int,
    private val numIntVars: Int,
    private val factors: Array<Factor>,
    private val propagators: Array<out Propagator>,
    private val invariants: () -> Array<out Invariant>,
) {
    /** Deductive occurrence lists: factor ids mentioning each Boolean variable, indexed by bool var id,
     *  excluding invariant-only factors ([NoPropagator]) so CP propagation never wakes them. */
    val boolOccurrences: Array<IntArray> = invert(numBoolVars, { propagators[it] !== NoPropagator }) { it.boolVars }

    /** Deductive occurrence lists over integer variables; see [boolOccurrences]. */
    val intOccurrences: Array<IntArray> = invert(numIntVars, { propagators[it] !== NoPropagator }) { it.intVars }

    /** True iff some factor is invariant-only ([NoPropagator]) — skipped by the CP occurrence lists.
     *  Lazy: only the LS occurrence lists below consult it, so a presolve/CP-only [Problem] never scans. */
    private val anyPropagatorAbsent: Boolean by lazy { propagators.any { it === NoPropagator } }

    /** True iff some factor is propagator-only ([NoInvariant]) — skipped by the LS occurrence lists.
     *  Lazy (and triggers [invariants]): only the LS occurrence lists below consult it. */
    private val anyInvariantAbsent: Boolean by lazy { invariants().any { it === NoInvariant } }

    /** Local-search occurrence lists over Boolean variables, excluding propagator-only factors
     *  ([NoInvariant]) so a move never touches them. Aliases [boolOccurrences] when no factor splits
     *  its roles (the common case — both lists are then every factor). Lazy: only the LS engine reads
     *  these, so a presolve/CP-only [Problem] never builds the LS-side lists. */
    val lsBoolOccurrences: Array<IntArray> by lazy {
        if (!anyPropagatorAbsent && !anyInvariantAbsent) {
            boolOccurrences
        } else {
            invert(numBoolVars, { invariants()[it] !== NoInvariant }) { it.boolVars }
        }
    }

    /** Local-search occurrence lists over integer variables; see [lsBoolOccurrences]. */
    val lsIntOccurrences: Array<IntArray> by lazy {
        if (!anyPropagatorAbsent && !anyInvariantAbsent) {
            intOccurrences
        } else {
            invert(numIntVars, { invariants()[it] !== NoInvariant }) { it.intVars }
        }
    }

    /**
     * [boolOccurrences] minus factors that use per-literal wakeup (see
     * [Propagator.initialBoolWatchers]). The propagation engine walks this list for
     * occurrence-driven wakeup, while watcher-using factors are woken via the
     * per-state [com.eignex.klause.propagation.BoolWatcherIndex.byLit]
     * index instead. Identical to [boolOccurrences] when no factor opts in.
     */
    val nonBoolWatcherBoolOccurrences: Array<IntArray> = run {
        val watcherFid = BooleanArray(factors.size)
        var any = false
        for (i in propagators.indices) {
            if (propagators[i].initialBoolWatchers != null) {
                watcherFid[i] = true
                any = true
            }
        }
        if (!any) {
            boolOccurrences
        } else {
            // Every variable's filtered list is live at once inside this constructor, so a boxing
            // `IntArray.filter` would hold the whole occurrence set as `Integer` objects (~24 bytes per
            // retained occurrence against 4 in the result) — hundreds of MB on clause-heavy models.
            Array(numBoolVars) { v -> retain(boolOccurrences[v]) { fid -> !watcherFid[fid] } }
        }
    }

    /** True iff some factor opts into typed int-domain event wakeup
     *  ([Propagator.initialIntEventWatches]). When false, the engine skips all int-event bookkeeping
     *  and [nonIntEventWatcherIntOccurrences] aliases [intOccurrences]. */
    val usesIntEventWatchers: Boolean = propagators.any { it.initialIntEventWatches != null }

    /** True iff some factor consumes the per-factor dirty-variable delta ([Propagator.consumesIntEventDelta]).
     *  When false the engine allocates no delta accumulators and the dirty-var bookkeeping is skipped. */
    val usesIntEventDeltaConsumers: Boolean = propagators.any { it.consumesIntEventDelta }

    /**
     * [intOccurrences] minus, per variable, the factors that subscribe to a typed int-event on
     * *that* variable (see [Propagator.initialIntEventWatches]). The propagation engine walks this list
     * for occurrence-driven int wakeup; a subscribing factor is woken for its subscribed variables
     * via the per-`(var, kind)`
     * [com.eignex.klause.propagation.IntEventMachinery.forEachWatcher] index instead.
     *
     * Exclusion is per `(factor, variable)`, not all-or-nothing: a factor that subscribes to events
     * on variable `a` but not `b` (both in its [Factor.intVars]) is dropped from `a`'s list yet kept
     * on `b`'s, so `b` still wakes it the normal way. Identical to [intOccurrences] when no factor
     * opts in.
     */
    val nonIntEventWatcherIntOccurrences: Array<IntArray> = if (!usesIntEventWatchers) {
        intOccurrences
    } else {
        // Per factor, the set of int vars it subscribes to an event on — built once in O(Σ watches).
        // The per-`(var, factor)` exclusion below is then an O(1) membership test, not a linear scan of
        // the factor's whole watch list: a single wide factor (a linear over thousands of vars watches
        // every one of them) appears in each of its vars' occurrence lists, so a naive scan costs
        // O(arity²) per such factor — a construction-time wedge on presolve's repeated problem
        // rebuilds over wide-linear instances.
        val watchedVarsByFactor = arrayOfNulls<IntHashSet>(factors.size)
        for (fid in propagators.indices) {
            val watches = propagators[fid].initialIntEventWatches ?: continue
            val set = IntHashSet(watches.size)
            for (w in watches) set.add(IntEvent.intVarOf(w))
            watchedVarsByFactor[fid] = set
        }
        // Primitive filter for the same reason as [nonBoolWatcherBoolOccurrences]: a boxing
        // `IntArray.filter` would materialize the whole occurrence set as `Integer` objects at once.
        Array(numIntVars) { v ->
            retain(intOccurrences[v]) { fid -> watchedVarsByFactor[fid]?.contains(v) != true }
        }
    }

    /** The elements of [src] satisfying [keep], in source order, without the per-element boxing of
     *  `IntArray.filter`. Binds the shared empty array when nothing survives. */
    private inline fun retain(src: IntArray, keep: (Int) -> Boolean): IntArray {
        var kept = 0
        for (fid in src) if (keep(fid)) kept++
        if (kept == 0) return EmptyIntArray
        val out = IntArray(kept)
        var k = 0
        for (fid in src) if (keep(fid)) out[k++] = fid
        return out
    }

    private inline fun invert(slots: Int, include: (Int) -> Boolean, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        factors.forEachIndexed { id, f -> if (include(id)) for (v in vars(f)) counts[v]++ }
        // A variable with no occurrences shares the empty singleton: a distinct 16-byte empty IntArray
        // per unoccurring variable is pure overhead on models with many such variables.
        val out = Array(slots) { if (counts[it] == 0) EmptyIntArray else IntArray(counts[it]) }
        val cursor = IntArray(slots)
        factors.forEachIndexed { id, f ->
            if (include(id)) for (v in vars(f)) out[v][cursor[v]++] = id
        }
        return out
    }
}
