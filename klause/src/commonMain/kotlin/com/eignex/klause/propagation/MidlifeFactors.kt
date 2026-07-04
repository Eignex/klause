package com.eignex.klause.propagation

import com.eignex.klause.solver.Factor
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Mid-life presolve factor overlay for [PropagationState.incremental] mode: a dedicated
 * append-only store for factors added during an incremental presolve bake. Distinct from
 * [LearnedClauseDb.store]: a presolve state never learns clauses and a search state never adds
 * presolve factors, so at most one of the two tail stores is non-empty and both occupy the id
 * range `[baseFactorCount, totalFactorCount)`. Ids are stable and never renumbered; dropping a
 * factor (base or mid-life) tombstones its id ([tombstoned]) and clears its `refPayload` slot.
 * A tombstoned slot is NEVER reused by a later add — reuse would resurrect a stale propagator
 * payload under a new factor id and silently corrupt a deduction (the wrong-optimum guard).
 */
internal class MidlifeFactors {
    /** The mid-life propagators, appended by `addMidlifeFactor` in factor-id order. */
    val store: ArrayList<Propagator> = ArrayList()

    /** The [Factor] behind each mid-life propagator, parallel to [store]. Carried because
     *  [Propagator] exposes no `boolVars` / `intVars`; the conflict and level machinery reads them
     *  here for a mid-life factor the way it reads [com.eignex.klause.solver.Problem.factors] for a base one. */
    val factors: ArrayList<Factor> = ArrayList()

    /** Tombstoned factor ids (base or mid-life). `factorAt` returns [NoPropagator] for a member,
     *  so a dropped factor is inert without removing it from the watcher indices — its id stays
     *  stable and is never reused. Empty until the first drop, so a state that only appends
     *  allocates nothing. */
    val tombstoned: IntHashSet = IntHashSet()

    /** Delta overlay for occurrence-list bool wakeup of mid-life factors: `[v]` lists mid-life
     *  factor ids that wake on any change to bool var `v` (those NOT using per-literal watchers).
     *  `null` until the first such factor is added — a search state and a watcher-only presolve
     *  never allocate it, so `enqueueForBoolChange` pays a single null-check. Complements
     *  [com.eignex.klause.solver.Problem.nonBoolWatcherBoolOccurrences]. */
    var boolOccurrences: Array<IntArrayList>? = null

    /** Int-side analog of [boolOccurrences]: `[v]` lists mid-life factor ids that wake on any
     *  change to int var `v` and do not subscribe to a typed int-event on `v`. Complements
     *  [com.eignex.klause.solver.Problem.nonIntEventWatcherIntOccurrences]. */
    var intOccurrences: Array<IntArrayList>? = null
}
