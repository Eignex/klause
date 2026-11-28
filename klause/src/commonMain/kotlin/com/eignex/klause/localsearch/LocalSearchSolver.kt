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
 * Local-search [Solver]. The solver carries only strategy and restart policy, which searches share, so searches run
 * sequentially; assignments, RNGs and payloads belong to each draw. Root propagation is the only source of
 * refutation. [enumerate] is a sample stream that may repeat solutions.
 */
class LocalSearchSolver(
    override val problem: BakedProblem,
    /** Strategy for the satisfy phase, and for minimization through its [FeasibleDescent] when [optimizeStrategy]
     *  is null. */
    val strategy: SourceDrivenStrategy = Cbls(),
    /** Strategy driving both phases of [minimize], such as [Cbls] beside a [ProbSat.adaptive] satisfy strategy;
     *  null reuses [strategy]. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    /** Restarts on models of at least 32 variables run one greedy pass setting each var to its cost-minimizing
     *  value. */
    val greedyRepairOnRestart: Boolean = true,
    /** Evaluates defined vars from the free ones after every restart, so only real constraints start violated. */
    val definitionalSweep: DefinitionalSweep? = null,
    /** Maintains [definitionalSweep]'s definitions after every move and excludes defined vars from move
     *  generation. Requires [definitionalSweep]. */
    val perMoveInvariants: Boolean = false,
    /** Seeds elected structural globals ([ImplicitSeeding.electedImplicit]) feasible after each restart, so their
     *  structure-preserving moves are productive from the first step. */
    val seedImplicitOnRestart: Boolean = false,
    /** Decides candidates of a model with LP-only continuous columns, which are scored only in floating point;
     *  such a model is declined without one. */
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

    /** A [LocalSearchSession] that keeps learned factor weights across calls. */
    override fun session(): LocalSearchSession = LocalSearchSession(this)

    /**
     * Best-effort minimization under the hard constraints. Local search is incomplete, so the verdict is
     * [MinimizeResult.BestFound] or [MinimizeResult.Unknown], except a model root propagation refutes, which is
     * [MinimizeResult.Infeasible].
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
