package com.eignex.klause.solver

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.count.AnytimeCounter
import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.ApproxMC
import com.eignex.klause.count.Count
import com.eignex.klause.count.CountConfig
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.count.UniGen
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.util.Cancellation

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
    /** Return a copy of these params with [assumptions] applied. */
    fun withAssumptions(@Suppress("UNUSED_PARAMETER") assumptions: Assumptions): SolverParams = this

    /** Inject a cooperative cancellation token. Backends that support cancellation
     *  override to return a copy with the token wired in; others (Brute)
     *  default to no-op. */
    fun withCancellation(@Suppress("UNUSED_PARAMETER") cancellation: Cancellation): SolverParams = this
}

/**
 * Outcome of a single-shot [Solver.solve] call.
 *
 *  - [Sat] — the engine found a satisfying assignment.
 *  - [Unsat] — the engine proved no assignment exists. Only complete backends (
 *    `BruteForceSolver`, `BacktrackSolver`) return this; the local-search engine
 *    returns [Unknown] when its budget is exhausted.
 *  - [Unknown] — the engine returned without a definitive answer (LS budget exhausted,
 *    timeout, etc.).
 */
sealed interface SolveResult {
    /** Snapshot of solver-side counters for this run. Defaults to [SolveStats.EMPTY] for
     *  backends that haven't opted in; populated by backends that have. */
    val stats: SolveStats

    /** Satisfiable, carrying a model. */
    data class Sat(
        /** The satisfying assignment. */
        val assignment: Sample,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : SolveResult

    /**
     * Proven infeasible. [core] is an optional jointly-unsat subset of factor ids; backends
     * that compute one populate it, backends that don't leave
     * it `null`. `Unsat()` (no core) is a valid construction.
     */
    data class Unsat(
        val core: UnsatCore? = null,
        override val stats: SolveStats = SolveStats.EMPTY,
        /**
         * When the engine was driven with `params.assumptions` non-empty and proved
         * UNSAT, the subset of those assumptions whose decision levels were touched by
         * any conflict's 1UIP analysis during the search. Sound (jointly infeasible
         * with the hard constraints) but not guaranteed minimal — populated by
         * [com.eignex.klause.backtrack.BacktrackSolver]; other backends leave
         * this `null`. Used by [com.eignex.klause.solver.result.satisfyUnderAssumptions] to surface a tight
         * [com.eignex.klause.solver.result.SatisfyResult.UnsatUnderAssumptions.core] without the
         * `minimizeCore = true` deletion-MUS fallback.
         */
        val assumptionCore: Assumptions? = null,
    ) : SolveResult

    /** Indeterminate (e.g. timeout or budget exhaustion). */
    data class Unknown(
        /** Why the result is indeterminate (e.g. timeout). */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : SolveResult
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
 *    `BruteForceSolver`): distinct satisfying assignments, with optional
 *    rolling-window post-filter via `params.minHammingDistance` / `params.recentWindow`.
 *    Stochastic backends (`LocalSearchSolver`) cannot enumerate; their `enumerate` is
 *    an alias for [samples] and may yield duplicates.
 */
interface Solver<P : SolverParams> {
    /** The problem this solver operates on — always a baked, solve-ready one. */
    val problem: BakedProblem

    /** Solve the problem once and return a [SolveResult]. */
    fun solve(params: P): SolveResult

    /** A one-line, human-readable description of this solver's resolved configuration under [params] —
     *  what the CLI `dry-run-solver` mode prints instead of solving. The default names the solver
     *  class; concrete backends override to surface their key knobs (selectors, restart, LP, strategy). */
    fun describe(params: P): String = this::class.simpleName ?: "solver"

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

    /** Lazily draw diverse samples. */
    fun samples(params: P): Sequence<Sample>

    /** Lazily enumerate distinct models. */
    fun enumerate(params: P): Sequence<Sample>

    /**
     * Approximate model count over [config]'s sampling set (all variables by default): a
     * probabilistic interval within a multiplicative `(1 ± ε)` factor at confidence `1 - δ`.
     * Backend-agnostic — XOR hashes are counted natively via ApproxMC; an integer projection is
     * channelled to Boolean bits the hashes range over (see [Count]).
     */
    fun approximateCount(config: ApproxCountConfig = ApproxCountConfig()): Count = ApproxMC.run(problem, config)

    /**
     * Anytime *exact* (projected) model counting: a lazy stream of deterministic [Count]s whose
     * interval tightens (`lower` ↑, `upper` ↓) until [Count.exact]. Iterate as far as you want;
     * each step resumes the feasibility search. Cheap when the count is small (it converges fast),
     * expensive when large — pair it with [approximateCount] via [count] for graceful degradation.
     */
    fun exactCount(config: ExactCountConfig = ExactCountConfig()): Sequence<Count> = AnytimeCounter.run(problem, config)

    /**
     * Best-effort count: run the exact counter up to [CountConfig.exactBudget] feasibility checks;
     * if it proves the count exactly, return that, otherwise fall back to [approximateCount] with
     * the estimate clamped into the exact phase's proven `[lower, upper]` (the hard bounds can only
     * sharpen the probabilistic answer). Exact when cheap, approximate when not.
     */
    fun count(config: CountConfig = CountConfig()): Count {
        val proven = exactCount(config.toExactConfig()).last()
        if (proven.exact) return proven
        val approx = approximateCount(config.toApproxConfig())
        val lower = maxOf(proven.lower, approx.lower)
        val upper = maxOf(lower, minOf(proven.upper, approx.upper))
        return Count(
            estimate = approx.estimate.coerceIn(lower, upper),
            lower = lower,
            upper = upper,
            exact = lower == upper,
            confidence = approx.confidence,
        )
    }

    /**
     * Quality-tiered sampling. [SampleQuality.CHEAP] (the default and the production path)
     * delegates to this backend's [samples]; [SampleQuality.ACCURATE] runs near-uniform UniGen2
     * XOR-hashing over the projection's bits (an accuracy-validation tool). Returns a lazy,
     * unbounded sequence — use `.take(n)`.
     */
    fun samples(config: SamplingConfig, params: P): Sequence<Sample> = when (config.quality) {
        SampleQuality.CHEAP -> samples(params)
        SampleQuality.ACCURATE -> UniGen.samples(problem, config) { samples(params) }
    }

    /**
     * Open a stateful [Session] against this solver. The default returns a
     * [StatelessSession] that manages an assumption stack but holds no other state;
     * backends can override to inject cross-call state (warm-start, learned clauses,
     * kumulant heuristic posteriors).
     */
    fun session(): Session<P> = StatelessSession(this)
}

/**
 * A [Solver] that also returns a feasible assignment minimising a [LinearObjective].
 *
 * Calls carry the objective per-invocation so the same backend can be reused across
 * differently-weighted optimisation queries (e.g. Thompson-sampled weight vectors).
 *
 * The objective is **statically** the native integer-linear form — the one every front-end
 * produces — so backends enable their objective machinery (LP relaxation bounding, branch-and-bound
 * bounds) from params alone, with no runtime objective-type
 * dispatch. The local-search engines additionally accept a per-move gradient view of the same
 * objective via `LocalSearchParams.lsObjective` (see
 * [com.eignex.klause.solver.objective.IncrementalObjective]).
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
    fun minimize(objective: LinearObjective, params: P): MinimizeResult

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
     * `solver.minimize(obj, p)` is equivalent to `solver.improvements(obj, p).last()`.
     *
     * Default implementation: a single-element sequence wrapping [minimize]. Backends
     * with an inner anytime loop ([BacktrackSolver], `LocalSearchSolver`) override to
     * yield each improvement as it lands.
     */
    fun improvements(objective: LinearObjective, params: P): Sequence<MinimizeResult> =
        sequenceOf(minimize(objective, params))
}
