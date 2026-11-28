package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit

/**
 * The deductive contract of a constraint: propagation, watcher subscriptions and conflict explanation. Built-in
 * projections can serve several states; their mutable work and failure premises belong to [PropagationState]. A
 * selected custom factory can open a private mechanism for one live engine state.
 */
interface Propagator {
    /**
     * Pin or tighten anything this factor implies under [state]'s current pins and domains; return `false` iff a
     * contradiction is derived. The default is a sound no-op.
     */
    fun propagate(state: PropagationState, factorId: Int): Boolean = true

    /**
     * Literals whose falsification wakes this factor, or `null` (the default) to wake on any change to its
     * `Factor.boolVars`. The factor keeps the index in sync as watches move, via
     * [com.eignex.klause.propagation.moveBoolWatcher]. [com.eignex.klause.factor.bool.Clause] uses it for
     * two-watched-literal propagation (Moskewicz et al., "Chaff: engineering an efficient SAT solver", DAC 2001,
     * https://doi.org/10.1145/378239.379017).
     */
    val initialBoolWatchers: IntArray? get() = null

    /**
     * Blocking literals aligned with [initialBoolWatchers]: a true blocker proves the factor satisfied, so the
     * wake is skipped. A stale blocker only costs a missed skip. Only sound for factors satisfied by any single
     * true literal, such as [com.eignex.klause.factor.bool.Clause]; it must stay `null` for cardinality.
     */
    val initialBoolWatcherBlockers: IntArray? get() = null

    /**
     * Typed integer-domain events, each `(intVar, kind)` encoded by [com.eignex.klause.propagation.IntEvent.pack],
     * that wake this factor; `null` (the default) wakes it on any change to its `Factor.intVars`. A
     * bounds-consistent factor can subscribe to [com.eignex.klause.propagation.IntEvent.LB_RAISED] / `UB_LOWERED`
     * only and skip interior removals.
     *
     * A variable named here leaves this factor's occurrence-list wakeup, so the subscription must cover every
     * kind the factor needs; an under-broad subscription silently drops a wake. Unnamed variables keep the
     * occurrence-list wakeup.
     */
    val initialIntEventWatches: IntArray? get() = null

    /**
     * Whether this factor consumes the dirty-variable delta: its subscribed variables that changed since it last
     * drained, read via [com.eignex.klause.propagation.PropagationState.drainIntEventDirtyVars], so per-fire work
     * scopes to the changed variables.
     *
     * A consumer must subscribe via [initialIntEventWatches] to every kind on every variable it depends on: a
     * variable enters the delta only when a subscribed advisor fires. The delta is a superset of the real
     * changes, since backtracking leaves stale entries for the consumer's reversible baseline to discard.
     */
    val consumesIntEventDelta: Boolean get() = false

    /**
     * Whether this factor's first fire builds heavy per-state bookkeeping (a table's live tuples, a flow or
     * matching cache, an automaton layer DP). A root bake with `skipExpensiveBake` skips such factors, which only
     * weakens the bake fixpoint; search re-derives the tightening on the final post-presolve factors.
     */
    val expensiveBake: Boolean get() = false

    /**
     * After [propagate] returned `false`, literals all currently false in [state] whose disjunction explains the
     * contradiction; conflict analysis seeds its resolution with them. Bool-pinning factors return a sharp
     * clause; int-domain factors cite order-literal atoms ([Lit] bounds and holes). `null` (the default) accepts
     * chronological backtracking: a coarse over-approximation would suppress learning under int decisions and is
     * unsound when the dead-end is not implied by bool pins alone.
     */
    fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null

    /**
     * Build the reason for a deduction this factor recorded lazily ([lazyReason]) at undo-log position [atTrail]
     * and decision level [atLevel], from [payload]: clause-form literals, false now, that imply it. Called only
     * by conflict analysis, through [reasonOf]; read bounds as they stood then with [boundAt].
     */
    fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int): IntArray? =
        error("${this::class.simpleName} recorded a lazy reason it cannot explain")
}

/**
 * The absence of a deductive role: an invariant-only factor that participates in local search but never filters
 * domains. It is dropped from the deductive occurrence lists, so propagation never wakes it.
 */
object NoPropagator : Propagator
