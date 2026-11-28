package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.count.AnytimeCounter
import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.ApproxMC
import com.eignex.klause.count.Count
import com.eignex.klause.count.CountConfig
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.count.accurateSamples
import com.eignex.klause.count.combineCounts
import com.eignex.klause.count.countingScope
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.util.ExecutionControl

/**
 * Backend-specific solver params; [Solver] and [Optimizer] are generic over the params type so each
 * backend receives its own.
 *
 * [Session] injects its assumption stack through [withAssumptions]. Parameters that support scopes
 * expose [assumptions] and override [withAssumptions]; the default rejects non-empty scopes.
 */
interface SolverParams {
    /** The per-call pins and deductions; empty for parameters without assumption support. */
    val assumptions: Assumptions get() = Assumptions.None

    /** Return a copy with [assumptions] merged, or reject a scope this backend cannot enforce. */
    fun withAssumptions(assumptions: Assumptions): SolverParams {
        if (!assumptions.isEmpty || !assumptions.deductions.isEmpty) {
            throw UnsupportedOperationException("these solver parameters do not support assumptions")
        }
        return this
    }

    /** Inject cancellation for one invocation, including resumable handle construction.
     *  Supporting backends retain any separately bound lifetime scope; others default to no-op. */
    fun withCancellation(@Suppress("UNUSED_PARAMETER") cancellation: ExecutionControl): SolverParams = this

    /** Add cancellation that remains bound through every invocation and resumable slice.
     *  Supporting backends compose lifetime tokens; backends without cancellation default to no-op. */
    fun withLifetimeCancellation(@Suppress("UNUSED_PARAMETER") cancellation: ExecutionControl): SolverParams = this
}

/**
 * Outcome of a single-shot [Solver.solve] call.
 *
 *  - [Sat] — the engine found a satisfying assignment.
 *  - [Unsat] — the engine proved no assignment exists. Complete backends can exhaust
 *    the search; local search can return a root-propagation refutation.
 *  - [Unknown] — the engine returned without a definitive answer (LS budget exhausted,
 *    timeout, etc.).
 */
sealed interface SolveResult {
    /** Effective source interpretation, or null for a result not bound to a source model. */
    val semantics: EffectiveSourceSemantics?

    /** Snapshot of solver-side counters for this run; [SolveStats.EMPTY] from backends that record none. */
    val stats: SolveStats

    /** Optional original-source claims and derivations; null when capture was not requested. */
    val evidence: SourceResultEvidence?

    /** Satisfiable, carrying a model. */
    data class Sat(
        /** The satisfying assignment. */
        val assignment: Sample,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = assignment.semantics,
    ) : SolveResult

    /**
     * Proven infeasible. [core] is an optional jointly-unsat subset of factor ids, `null` from
     * backends that do not compute one.
     */
    data class Unsat(
        val core: UnsatCore? = null,
        override val stats: SolveStats = SolveStats.EMPTY,
        /**
         * A jointly refuted, not necessarily minimal, subset of `params.assumptions`, or `null` when the
         * backend computes none.
         */
        val assumptionCore: Assumptions? = null,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SolveResult

    /** Indeterminate (e.g. timeout or budget exhaustion). */
    data class Unknown(
        /** Why the result is indeterminate (e.g. timeout). */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SolveResult
}

/**
 * Backend that produces satisfying assignments for a `Problem`. Four entry points:
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

    /** A one-line description of this solver's resolved configuration under [params]. */
    fun describe(params: P): String = this::class.simpleName ?: "solver"

    /**
     * Draw one sample: [SampleResult.Found] when [samples] yields, otherwise [SampleResult.Unknown].
     */
    fun sample(params: P): SampleResult {
        val s = openSamples(params).use { it.firstOrNull() }
        val result = if (s != null) {
            SampleResult.Found(s)
        } else {
            SampleResult.Unknown(TerminationReason.BudgetExhausted)
        }
        return result.withSourceSemantics(problem.semantics)
    }

    /** Lazily draw diverse samples. */
    fun samples(params: P): Sequence<Sample>

    /** Lazily enumerate distinct models. */
    fun enumerate(params: P): Sequence<Sample>

    /** Open samples with deterministic cleanup; close when stopping early. */
    fun openSamples(params: P): SearchStream<Sample> = samples(params).asSearchStream()

    /** Open enumeration with deterministic cleanup; close when stopping early. */
    fun openEnumerate(params: P): SearchStream<Sample> = enumerate(params).asSearchStream()

    /** Open a quality-tiered sample cursor; close when stopping early. */
    fun openSamples(config: SamplingConfig, params: P): SearchStream<Sample> =
        when (config.quality) {
            SampleQuality.CHEAP -> openSamples(params)
            SampleQuality.ACCURATE -> samples(config, params).asSearchStream()
        }

    /** Open an exact-count cursor; close when stopping early. */
    fun openExactCount(config: ExactCountConfig = ExactCountConfig()): SearchStream<Count> =
        exactCount(config).asSearchStream()

    /**
     * Approximate model count over [config]'s sampling set (all variables by default): a
     * probabilistic interval within a multiplicative `(1 ± ε)` factor at confidence `1 - δ`.
     * Backend-agnostic — XOR hashes are counted natively via ApproxMC; an integer projection is
     * channelled to Boolean bits the hashes range over (see [Count]).
     */
    fun approximateCount(config: ApproxCountConfig = ApproxCountConfig()): Count = ApproxMC.run(problem, config)

    /**
     * Anytime exact (projected) model counting: a lazy stream of [Count]s whose interval tightens until
     * [Count.exact]. Each step resumes the feasibility search; [count] falls back to [approximateCount].
     */
    fun exactCount(config: ExactCountConfig = ExactCountConfig()): Sequence<Count> = AnytimeCounter.run(problem, config)

    /**
     * Best-effort count: the exact counter for up to [CountConfig.exactBudget] feasibility checks, otherwise
     * [approximateCount] clamped into the exact phase's proven `[lower, upper]`.
     */
    fun count(config: CountConfig = CountConfig()): Count =
        combineCounts(exactCount(config.toExactConfig()).last()) { approximateCount(config.toApproxConfig()) }

    /**
     * Quality-tiered sampling. [SampleQuality.CHEAP] delegates to [samples]; [SampleQuality.ACCURATE] runs
     * near-uniform UniGen2 XOR-hashing over the projection's bits. The sequence is lazy and unbounded.
     */
    fun samples(config: SamplingConfig, params: P): Sequence<Sample> = when (config.quality) {
        SampleQuality.CHEAP -> samples(params)
        SampleQuality.ACCURATE -> problem.countingScope(params.assumptions).accurateSamples(config) { samples(params) }
    }

    /**
     * Open a stateful [Session] against this solver. The default [StatelessSession] holds only an
     * assumption stack; backends override to retain cross-call state.
     */
    fun session(): Session<P> = StatelessSession(this)
}

/**
 * A [Solver] that also returns a feasible assignment minimising a [LinearObjective].
 *
 * The objective is passed per call, so one backend serves differently weighted queries.
 */
interface Optimizer<P : SolverParams> : Solver<P> {
    /**
     * Optimise the assignment against [objective] under the hard constraints. Verdict:
     *  - [MinimizeResult.Optimal] — feasible found and proved optimal (search exhausted
     *    or bound proves no better exists).
     *  - [MinimizeResult.BestFound] — feasible found but optimality not proven; carries
     *    the [TerminationReason] that stopped the search.
     *  - [MinimizeResult.Unbounded] — feasible found with a ray along which the objective
     *    descends without limit.
     *  - [MinimizeResult.Infeasible] — no feasible exists.
     *  - [MinimizeResult.Unknown] — no feasible found, no infeasibility proven.
     *
     * Local-search backends return [MinimizeResult.Infeasible] only for a root-propagation refutation
     * and cannot return [MinimizeResult.Optimal].
     */
    fun minimize(objective: LinearObjective, params: P): MinimizeResult

    /**
     * Streaming variant of [minimize]: one [MinimizeResult.BestFound] per new incumbent, then one terminal
     * result as [minimize] would return it, so `minimize(obj, p)` equals `improvements(obj, p).last()`.
     * The default yields only [minimize]'s result.
     */
    fun improvements(objective: LinearObjective, params: P): Sequence<MinimizeResult> =
        sequenceOf(minimize(objective, params))

    /** Open an incumbent cursor with deterministic cleanup; close when stopping early. */
    fun openImprovements(objective: LinearObjective, params: P): SearchStream<MinimizeResult> =
        improvements(objective, params).asSearchStream()
}
