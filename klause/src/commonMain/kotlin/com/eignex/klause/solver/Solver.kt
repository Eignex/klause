package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackSolver

/**
 * Marker for backend-specific solver params. Each solver backend ships its own data class
 * implementing this; the [Solver] / [Optimizer] interfaces are generic over the params type
 * so the type system enforces the right params reach the right backend.
 *
 * [withAssumptions] is used by the [Session] abstraction to inject a stacked set of
 * pinned variables into a per-call params object without each backend needing to know
 * about Session. The default no-op returns `this` unchanged — appropriate for backends
 * whose params don't carry an `assumptions` field. Backends that do (LocalSearchParams,
 * BacktrackParams) override to return a copy with the merged pins applied.
 */
interface SolverParams {
    fun withAssumptions(@Suppress("UNUSED_PARAMETER") assumptions: Assumptions): SolverParams = this

    /** Inject a cooperative cancellation token. Backends that support cancellation
     *  override to return a copy with the token wired in; others (LogicNG, Z3, Brute)
     *  default to no-op. */
    fun withCancellation(@Suppress("UNUSED_PARAMETER") cancellation: Cancellation): SolverParams = this
}

/**
 * Outcome of a single-shot [Solver.solve] call.
 *
 *  - [Sat] — the engine found a satisfying assignment.
 *  - [Unsat] — the engine proved no assignment exists. Only complete backends (LogicNG,
 *    Z3, `BruteForceSolver`, `BacktrackSolver`) return this; the local-search engine
 *    returns [Unknown] when its budget is exhausted.
 *  - [Unknown] — the engine returned without a definitive answer (LS budget exhausted,
 *    timeout, etc.).
 */
sealed interface SolveResult {
    /** Snapshot of solver-side counters for this run. Defaults to [SolveStats.EMPTY] for
     *  backends that haven't opted in; populated by backends that have. */
    val stats: SolveStats

    data class Sat(val assignment: Sample, override val stats: SolveStats = SolveStats.EMPTY) : SolveResult

    /**
     * Proven infeasible. [core] is an optional jointly-unsat subset of factor ids; backends
     * that compute one populate it (Z3 via tracked assertions), backends that don't leave
     * it `null`. `Unsat()` (no core) is a valid construction.
     */
    data class Unsat(
        val core: UnsatCore? = null,
        override val stats: SolveStats = SolveStats.EMPTY,
        /**
         * When the engine was driven with [params.assumptions] non-empty and proved
         * UNSAT, the subset of those assumptions whose decision levels were touched by
         * any conflict's 1UIP analysis during the search. Sound (jointly infeasible
         * with the hard constraints) but not guaranteed minimal — populated by
         * [com.eignex.klause.solver.backtrack.BacktrackSolver]; other backends leave
         * this `null`. Used by [satisfyUnderAssumptions] to surface a tight
         * [SatisfyResult.UnsatUnderAssumptions.core] without the
         * `minimizeCore = true` deletion-MUS fallback.
         */
        val assumptionCore: Assumptions? = null,
    ) : SolveResult
    data class Unknown(val reason: TerminationReason, override val stats: SolveStats = SolveStats.EMPTY) : SolveResult
}

/**
 * Backend that produces satisfying assignments for a [Problem]. Four entry points:
 *
 *  - [solve] — single-shot SAT/UNSAT/Unknown.
 *  - [sample] — first satisfying assignment, or `null` if the engine couldn't find one
 *    within its budget. Default implementation takes the first yield of [samples];
 *    backends with a cheaper one-shot path may override.
 *  - [samples] — *with replacement*. Each yield is an independent draw; the same
 *    assignment may reappear. Dedup fields on [P] (where present) are ignored.
 *  - [enumerate] — *without replacement* for complete backends ([BacktrackSolver],
 *    [BruteForceSolver], LogicNG, Z3): distinct satisfying assignments, with optional
 *    rolling-window post-filter via `params.minHammingDistance` / `params.recentWindow`.
 *    Stochastic backends ([LocalSearchSolver]) cannot enumerate; their `enumerate` is
 *    an alias for [samples] and may yield duplicates.
 */
interface Solver<P : SolverParams> {
    val problem: Problem
    fun solve(params: P): SolveResult

    /**
     * Default implementation drains [samples] for one yield. Wraps it in
     * [SampleResult.Found] when the sequence yields, [SampleResult.Unknown] when it
     * doesn't. Backends with a cheaper one-shot path that can also distinguish
     * Infeasible (proven) from Unknown (budget) should override.
     */
    fun sample(params: P): SampleResult {
        val s = samples(params).firstOrNull()
        return if (s != null) {
            SampleResult.Found(s)
        } else {
            SampleResult.Unknown(TerminationReason.BudgetExhausted)
        }
    }
    fun samples(params: P): Sequence<Sample>
    fun enumerate(params: P): Sequence<Sample>

    /**
     * Open a stateful [Session] against this solver. The default returns a
     * [StatelessSession] that manages an assumption stack but holds no other state;
     * backends can override to inject cross-call state (warm-start, learned clauses,
     * kumulant heuristic posteriors).
     */
    fun session(): Session<P> = StatelessSession(this)
}

/**
 * A [Solver] that also returns a feasible assignment minimising a linear (or other)
 * objective.
 *
 * Calls carry the [Objective] per-invocation so the same backend can be reused across
 * differently-weighted optimisation queries (e.g. Thompson-sampled weight vectors).
 *
 * Backends typically specialise on [LinearObjective] for a fast path (incremental delta in
 * local search, native `mkAdd` translation in Z3) and either fall back to
 * [Objective.evaluate] for arbitrary subtypes or refuse to optimise them.
 */
interface Optimizer<P : SolverParams> : Solver<P> {
    /**
     * Optimise the assignment against [objective] under the hard constraints. Verdict:
     *  - [MinimizeResult.Optimal] — feasible found and proved optimal (search exhausted
     *    or bound proves no better exists).
     *  - [MinimizeResult.BestFound] — feasible found but optimality not proven; carries
     *    the [TerminationReason] that stopped the search.
     *  - [MinimizeResult.Infeasible] — no feasible exists.
     *  - [MinimizeResult.Unknown] — no feasible found, no infeasibility proven.
     *
     * Local-search backends can never return [MinimizeResult.Optimal] or
     * [MinimizeResult.Infeasible].
     */
    fun minimize(objective: Objective, params: P): MinimizeResult

    /**
     * Streaming variant of [minimize]: yields one [MinimizeResult] per *new incumbent*
     * discovered during the search, followed by a single terminal result describing how
     * the search ended.
     *
     *  - Each non-terminal yield is a [MinimizeResult.BestFound] carrying the new best
     *    sample and objective seen so far.
     *  - The terminal yield is one of: [MinimizeResult.Optimal] (search proved
     *    optimality), [MinimizeResult.Infeasible] (no feasible exists),
     *    [MinimizeResult.BestFound] with the final reason (budget / timeout /
     *    cancellation hit while holding a feasible), or [MinimizeResult.Unknown]
     *    (search ended without proving anything, no feasible found).
     *
     * Lets long-running optimizations report progress without callbacks or polling.
     * `solver.minimize(obj, p)` is now equivalent to `solver.improvements(obj, p).last()`.
     *
     * Default implementation: a single-element sequence wrapping [minimize]. Backends
     * with an inner anytime loop ([BacktrackSolver], [LocalSearchSolver]) override to
     * yield each improvement as it lands.
     */
    fun improvements(objective: Objective, params: P): Sequence<MinimizeResult> =
        sequenceOf(minimize(objective, params))
}
