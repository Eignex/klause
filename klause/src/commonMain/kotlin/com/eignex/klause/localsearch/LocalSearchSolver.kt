package com.eignex.klause.localsearch

import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.compile
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.count.accurateSamples
import com.eignex.klause.count.countingScope
import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.ResumableSolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchStream
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SourceStrategy
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult

/**
 * Local-search [Solver] around a `Problem`. The solver itself only carries engine setup
 * (strategy, restart cadence). Assignments, RNGs and factor payloads belong to each draw.
 * Searches must use the solver sequentially because strategy and restart policy are shared.
 * A resumable handle rejects other searches until it completes, fails or is closed.
 *
 * Three call kinds, each accepting a [LocalSearchParams]:
 *
 *  - [solve] — return a single [SolveResult]; root propagation is its only source of refutation.
 *  - [minimize] / [improvements] — best-effort optimisation of a linear objective.
 *  - [sample] / [enumerate] — both stream independent feasible draws with replacement.
 *    Local search has no notion of a "next" model, so enumerate is just a sample stream;
 *    duplicates may appear. Use [com.eignex.klause.backtrack.BacktrackSolver] when
 *    true without-replacement enumeration is required.
 */
class LocalSearchSolver(
    override val problem: BakedProblem,
    /** SourceDrivenStrategy for the satisfy phase (and, when [optimizeStrategy] is null, the minimize
     *  phase too, via its [SourceDrivenStrategy.feasibleDescent]). Default is [Cbls], whose
     *  [FeasibleDescent.SelfOwned] descent (greedy over its objective / structured / pair-swap sources)
     *  optimizes with no extra wiring. Override for a different arm — `ProbSat.adaptive()` for a boolean
     *  core (ratcheted on a COP by the portfolio), `SimulatedAnnealing.optimizer(...)` to anneal. */
    val strategy: SourceDrivenStrategy = Cbls(),
    /** SourceDrivenStrategy driving both phases of [minimize]; `null` reuses [strategy]. Override to
     *  decouple satisfy-mode and minimize-mode strategies, e.g. satisfy [ProbSat.adaptive] + minimize [Cbls]. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    /** When true (default), restarts on models of at least 32 variables run a greedy-repair pass after
     *  randomizing so the search starts closer to feasibility: one pass over the vars in randomized order,
     *  each set to the value that minimizes the current cost
     *  ([com.eignex.klause.localsearch.movesource.GreedyInit]). */
    val greedyRepairOnRestart: Boolean = true,
    /** Optional definitional sweep (see [DefinitionalSweep]): after every restart's randomization,
     *  defined (aux) vars are *evaluated* bottom-up from the free decision vars instead of left
     *  random, so decomposed models start each restart at the "only real constraints violated"
     *  frontier. */
    val definitionalSweep: DefinitionalSweep? = null,
    /** Per-move one-way invariants (opt-in): maintain [definitionalSweep]'s definitions incrementally
     *  after every applied move and exclude defined vars from move generation, shrinking the move
     *  space to true decision variables. Requires [definitionalSweep]; the restart-time sweep stays
     *  active as the full (re)initializer. */
    val perMoveInvariants: Boolean = false,
    /** Implicit-solving feasible init (opt-in): after each restart's randomization the engine seeds
     *  elected structural globals (see [ImplicitSeeding.electedImplicit]) into a feasible
     *  configuration via [com.eignex.klause.localsearch.Invariant.seedFeasible] — an all-different becomes a
     *  partial permutation, a circuit a single tour — so the search starts inside those constraints'
     *  feasible region and their structure-preserving moves are productive from the first step. */
    val seedImplicitOnRestart: Boolean = false,
    /** Decides each candidate of a model with LP-only continuous columns, whose rows local search scores only in
     *  floating point. Without one such a model is declined; a model without continuous columns never consults
     *  it. */
    val completion: CandidateCompletion? = null,
) : ResumableSolver<LocalSearchParams>,
    ResumableOptimizer<LocalSearchParams> {

    /** Source-aware construction retaining the original model. */
    companion object {
        /** Select this strategy with shared source preparation and exact witness completion. */
        fun source(problem: Problem): SourceStrategy = SourceStrategy(problem, FiniteEngine.LOCAL_SEARCH)

        /** Select this strategy for the original compiled source, including continuous products. */
        fun source(compiled: CompiledSchema): SourceStrategy =
            SourceStrategy(compiled.sourceBinding.originalProblem, FiniteEngine.LOCAL_SEARCH, compiled)

        /** Compile once and select this strategy for the resulting original source. */
        fun source(schema: VariableSchema): SourceStrategy = source(schema.compile())
    }

    /** Finite-only facade over a [CompiledSchema]; use [source] for shared model breadth. */
    @Deprecated("Finite-only facade; use LocalSearchSolver.source(compiled) for shared source execution.")
    constructor(compiled: CompiledSchema) : this(compiled.problem.bake())

    /** Compile [schema] into this finite-only facade; use [source] for shared model breadth. */
    @Deprecated("Finite-only facade; use LocalSearchSolver.source(schema) for shared source execution.")
    constructor(schema: VariableSchema) : this(schema.compile().problem.bake())

    internal val engine: LocalSearchEngine = LocalSearchEngine(
        LocalSearchModel.of(problem),
        strategy = strategy,
        optimizeStrategy = optimizeStrategy,
        restartPolicy = restartPolicy,
        greedyRepairOnRestart = greedyRepairOnRestart,
        definitionalSweep = definitionalSweep,
        perMoveInvariants = perMoveInvariants,
        seedImplicitOnRestart = seedImplicitOnRestart,
        completion = completion,
    )

    /** Objective-as-constraint ratchet handle; see [LocalSearchEngine.objectiveBound]. */
    internal var objectiveBound: MutableObjectiveBound?
        get() = engine.objectiveBound
        set(value) {
            engine.objectiveBound = value
        }

    override fun describe(params: LocalSearchParams): String = engine.describe(params)

    override fun solve(params: LocalSearchParams): SolveResult = engine.solve(params, warm = null)

    /**
     * Open a satisfaction walk whose assignment, RNG and restart state persist between slices. Consume and close
     * the handle before another search uses this solver's strategy and restart policy. Opening an overlapping
     * handle or executing another search throws [IllegalStateException]. Completion and failure release ownership.
     */
    override fun resumableSolve(params: LocalSearchParams): ResumableSolve = engine.resumableSolve(params)

    /**
     * Open an optimisation walk retaining its assignment, random state and restart policy across slices.
     * Opening an overlapping handle or executing another search throws [IllegalStateException]. Completion,
     * failure and [ResumableSearch.close] release ownership.
     */
    override fun resumable(objective: LinearObjective, params: LocalSearchParams): ResumableSearch =
        engine.resumable(objective, params)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = engine.samples(params, warm = null)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> = engine.samples(params, warm = null)

    override fun openSamples(params: LocalSearchParams): SearchStream<Sample> = engine.openSamples(params, warm = null)

    override fun openEnumerate(params: LocalSearchParams): SearchStream<Sample> =
        engine.openSamples(params, warm = null)

    override fun openSamples(config: SamplingConfig, params: LocalSearchParams): SearchStream<Sample> =
        openSamples(config, params, warm = null)

    internal fun openSamples(
        config: SamplingConfig,
        params: LocalSearchParams,
        warm: WarmState?,
    ): SearchStream<Sample> =
        when (config.quality) {
            SampleQuality.CHEAP -> engine.openSamples(params, warm)
            SampleQuality.ACCURATE -> engine.openSamples(params, warm) { fallback ->
                problem.countingScope(params.assumptions).accurateSamples(config) { fallback }
            }
        }

    override fun openImprovements(
        objective: LinearObjective,
        params: LocalSearchParams,
    ): SearchStream<MinimizeResult> =
        engine.openImprovements(objective, params, warm = null)

    /** Return a [LocalSearchSession] that persists learned factor weights across calls and maintains an
     *  assumption stack. */
    override fun session(): LocalSearchSession = LocalSearchSession(this)

    /**
     * Best-effort linear-objective minimisation under hard constraints; see [LocalSearchEngine.improvements].
     * Local search is incomplete, so the verdict is [MinimizeResult.BestFound] or [MinimizeResult.Unknown],
     * except a model root propagation refutes, which is [MinimizeResult.Infeasible].
     */
    override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
        engine.improvements(objective, params, warm = null).last()

    override fun improvements(objective: LinearObjective, params: LocalSearchParams): Sequence<MinimizeResult> =
        engine.improvements(objective, params, warm = null)

    /** Solve once and return a [SolveResult]. */
    fun solve(): SolveResult = solve(LocalSearchParams())

    /** Draw a single sample. */
    fun sample(): SampleResult = sample(LocalSearchParams())

    /** Lazily draw diverse samples. */
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())

    /** Lazily draw samples; unlike a complete enumeration, duplicates may appear. */
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())

    /** Optimise against [objective] under the hard constraints. */
    fun minimize(objective: LinearObjective): MinimizeResult = minimize(objective, LocalSearchParams())
}
