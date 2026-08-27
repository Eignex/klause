package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit

/**
 * The deductive contract of a constraint: propagation, watcher subscriptions, and conflict
 * explanation. Constructed by `Factor.asPropagator` and used by the CP engine
 * ([com.eignex.klause.backtrack.BacktrackSolver]) when dispatching to factors.
 *
 * See `Factor` for the full constraint contract (structural + deductive + local-search).
 */
interface Propagator {
    /**
     * Deductive propagation given [state]'s current pins / domains. Pin or tighten anything
     * this factor implies; return `false` iff a contradiction is derived. Default is a no-op
     * — sound but trivial. Factors override to participate in `Problem.propagate`.
     */
    fun propagate(state: PropagationState, factorId: Int): Boolean = true

    /**
     * Boolean literals this factor wants per-literal wakeup on, or `null` for the default
     * occurrence-list wakeup (fire on *any* change to a variable in the corresponding
     * `Factor.boolVars`). When non-null, the propagation engine routes bool wakeups through
     * a per-literal index (`watches.byLit[lit]`) instead of through the factor's bool vars:
     * the factor fires only when the literal that just became *false* is in this set. The
     * factor is responsible for keeping the index in sync as watches drift, via
     * [com.eignex.klause.propagation.moveBoolWatcher].
     *
     * Used by [com.eignex.klause.factor.bool.Clause] to implement two-watched-literal
     * propagation (Zhang–Stickel): only the two watched literals trigger
     * wakeups, so a 50-literal clause fires on 2/50 var changes instead of 50/50. The
     * same scheme generalises to Cardinality with k+1 watched literals — a future
     * factor can adopt this contract without engine changes.
     *
     * Default is `null` — preserves the current "wake on any boolVars change" semantics
     * for every factor that hasn't opted in.
     */
    val initialBoolWatchers: IntArray? get() = null

    /**
     * Optional blocking literals paired index-for-index with [initialBoolWatchers]. Entry
     * `i` is a literal that, if currently true, *proves this factor already satisfied*, so
     * the propagation engine can skip waking the factor when watcher `i`'s literal goes
     * false (see `PropagationState.watches.blockersByLit`). The standard two-watched-literal
     * BCP speedup: the blocker is typically the other watched literal of the same
     * clause, and a stale blocker only ever costs a missed skip — never correctness.
     *
     * Only meaningful for factors satisfied by *any* single true literal (disjunctions /
     * [com.eignex.klause.factor.bool.Clause]). Must stay `null` for factors where one true
     * literal does not imply satisfaction — e.g. cardinality, where a blocker would be
     * unsound. `null` (default) means "no blocking literals": every watcher always fires.
     */
    val initialBoolWatcherBlockers: IntArray? get() = null

    /**
     * Typed integer-domain events this factor wants advisor-style wakeup on, or `null` (the
     * default) for the occurrence-list wakeup — fire on *any* change to a variable in the
     * corresponding `Factor.intVars`. Each entry encodes a `(intVar, kind)` pair via
     * [com.eignex.klause.propagation.IntEvent.pack], where `kind` is one of
     * `IntEvent.LB_RAISED` / `UB_LOWERED` / `VALUE_REMOVED` / `FIXED`. When non-null, the engine
     * routes wakeup for the subscribed variables through the per-`(var, kind)` index
     * (`PropagationState.intEvents.forEachWatcher`) instead of through the factor's int vars: the
     * factor fires only when a kind it subscribed to actually occurs on that variable.
     *
     * This is the int-side analog of [initialBoolWatchers] and the scheduling substrate for
     * incremental propagators: a bounds-consistent factor can subscribe to only
     * [com.eignex.klause.propagation.IntEvent.LB_RAISED] / `UB_LOWERED` and skip waking on
     * interior value removals it cannot act on; a factor that only reacts to assignment can
     * subscribe to `FIXED` alone. A variable named here is removed from this factor's
     * occurrence-list wakeup (see [PropagationProblem.nonIntEventWatcherIntOccurrences])
     * — so the subscription must cover every kind the factor needs to stay correct; an under-broad
     * subscription silently drops a wake. A variable in `Factor.intVars` but *not* named here keeps
     * its normal occurrence-list wakeup.
     *
     * Default is `null` — preserves the current "wake on any intVars change" semantics for every
     * factor that hasn't opted in (and the engine pays nothing when no factor in the problem does).
     */
    val initialIntEventWatches: IntArray? get() = null

    /**
     * Whether this factor consumes the engine-maintained **dirty-variable delta**: the set of
     * its subscribed variables that changed since it last drained, retrieved on a fire via
     * [com.eignex.klause.propagation.PropagationState.drainIntEventDirtyVars]. A
     * domain-sensitive incremental propagator (Régin/GCC/Table/…) sets this `true` so it can scope
     * its per-fire work to the changed variables instead of scanning all of `Factor.intVars`.
     *
     * **Contract:** a consumer must also subscribe via [initialIntEventWatches] to *every* kind on
     * *every* variable it depends on — the engine only accumulates a variable into the delta when an
     * advisor it subscribed to fires, so an under-broad subscription drops a change and is unsound.
     * The accumulated set is a *superset* of "changed since last fire" (a backtrack leaves stale
     * entries, harmless because the consumer diffs its own reversible baseline). Default `false`:
     * the factor gets typed wakeup (if it subscribes) but the engine accumulates no delta for it.
     */
    val consumesIntEventDelta: Boolean get() = false

    /**
     * Whether this factor's first fire is expensive — it builds heavy per-state bookkeeping
     * (a table's live-tuple set, a flow/matching cache, an automaton layer DP) and sweeps it.
     * The root bake can skip firing such factors (see [PropagationState.runToFixpoint]'s
     * `skipExpensiveBake`): their optional root tightening is deferred to the first search fire,
     * where the state builds once on the final post-presolve factors instead of throwaway at load.
     * Skipping only weakens the bake fixpoint (always sound); search re-derives the tightening.
     * Default `false` — a lightweight factor (Linear, Clause, Comparison) always fires.
     */
    val expensiveBake: Boolean get() = false

    /**
     * If this factor just returned `false` from [propagate], the clause-form explanation
     * of why — i.e. an array of literals, all currently *false* in [state], whose
     * disjunction is unsatisfied. The propagation-graph conflict analyzer seeds its
     * resolution loop with this set when computing a learned clause (lazy clause
     * generation). Returns `null` for factors that can't produce a clause-form reason;
     * the analyzer falls back to chronological backtrack in that case.
     *
     * Every factor must declare its own explanation: bool-pinning factors (Clause,
     * Cardinality, PseudoBoolean, Xor, …) return a sharp factor-specific clause, and
     * int-domain factors (Linear, AllDifferent, GlobalCardinality, Element, Cumulative,
     * …) cite the order-literal atoms ([Lit] bounds/holes) that pinned the dead-end. A
     * factor with no sharp reason returns `null` and accepts chronological backtrack
     * rather than a coarse over-approximation, which would suppress learning under int
     * decisions and risks unsoundness if the dead-end is not implied by bool pins alone.
     */
    fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
}

/**
 * The absence of a deductive role. A factor whose `Factor.asPropagator` returns this is
 * **invariant-only**: it participates in local search but never filters domains. The CP
 * engine skips such factors entirely — they are dropped from the deductive occurrence lists
 * ([PropagationProblem.boolOccurrences] / [PropagationProblem.intOccurrences]) so propagation never wakes them. All
 * methods keep the no-op [Propagator] defaults (propagate is a no-op, no watches, no reason).
 */
object NoPropagator : Propagator
