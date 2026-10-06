package com.eignex.klause.localsearch

import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult

/**
 * Local-search [Solver] around a `Problem`. The solver itself only carries engine setup
 * (strategy, restart cadence). All per-draw state — RNG, assignment, factor payloads, the
 * dedup window — lives inside the per-call sequences so concurrent draws never share state.
 *
 * Three call kinds, each accepting a [LocalSearchParams]:
 *
 *  - [solve] — return a single [SolveResult]; LS never reports `Unsat`.
 *  - [sample] / [enumerate] — both stream independent feasible draws with replacement.
 *    Local search has no notion of a "next" model, so enumerate is just a sample stream;
 *    duplicates may appear. Use [com.eignex.klause.backtrack.BacktrackSolver] when
 *    true without-replacement enumeration is required.
 */
class LocalSearchSolver(
    override val problem: BakedProblem,
    /** SourceDrivenStrategy for the satisfy phase (and, when [optimizeStrategy] is null, the minimize
     *  phase too, via its [SourceDrivenStrategy.feasibleDescent]). Default is [Cbls] — its
     *  [FeasibleDescent.SelfOwned] descent (greedy over its objective / structured / pair-swap sources)
     *  runs the objective optimize, so a bare `LocalSearchSolver(problem).minimize(...)` optimizes with
     *  no extra wiring. Override for a
     *  different arm — `ProbSat.adaptive()` for a boolean core (ratcheted on a COP by the portfolio),
     *  `SimulatedAnnealing.optimizer(...)` to anneal. */
    val strategy: SourceDrivenStrategy = Cbls(),
    /** SourceDrivenStrategy for the feasibility-fight phase of [minimize]. `null` reuses [strategy].
     *  Override to decouple satisfy-mode and minimize-mode strategies; e.g. satisfy [ProbSat.adaptive] +
     *  minimize [Cbls] for decomposed CP problems, where CBLS's weighted-violation gradient descends the
     *  objective on instances where probSAT plateaus. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    /** When true (default), restarts run a greedy-repair pass after randomizing so the search starts
     *  closer to feasibility. The pass walks vars in randomized order and picks the value that
     *  minimizes immediate violation contribution. Idempotent and bounded by the variable count. */
    val greedyRepairOnRestart: Boolean = true,
    /** Optional definitional sweep (see [DefinitionalSweep]): after every restart's randomization,
     *  defined (aux) vars are *evaluated* bottom-up from the free decision vars instead of left
     *  random, so decomposed models start each restart at the "only real constraints violated"
     *  frontier. Null = behavior unchanged. */
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
) : Solver<LocalSearchParams>,
    Optimizer<LocalSearchParams> {

    /** Solve a [CompiledSchema]'s problem with the default local-search configuration. */
    constructor(compiled: CompiledSchema) : this(compiled.problem.bake())

    /** Compile [schema] with the default config and solve the resulting problem. */
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

    /** Objective-as-constraint ratchet handle (opt-in). Set non-null only for an arm whose [problem]
     *  carries an [com.eignex.klause.factor.objective.ObjectiveBoundFactor] sharing this bound: on each
     *  feasible incumbent the minimize loop tightens it below the incumbent, so the objective slack
     *  re-enters the violation set and the feasibility fight repairs it — the SAT→optimization ratchet
     *  for the violation-native arms (probSAT / WalkSAT). Null leaves objective handling unchanged. */
    internal var objectiveBound: MutableObjectiveBound?
        get() = engine.objectiveBound
        set(value) {
            engine.objectiveBound = value
        }

    override fun describe(params: LocalSearchParams): String = engine.describe(params)

    override fun solve(params: LocalSearchParams): SolveResult = engine.solve(params, warm = null)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = engine.samples(params, warm = null)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> = engine.samples(params, warm = null)

    /** Return a [LocalSearchSession] that persists DDFW-style factor weights across
     *  calls and maintains an assumption stack. Backend-specific override of
     *  [Solver.session]'s default `StatelessSession`. */
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

    /** Draw a single diverse sample, or null if none exists. */
    fun sample(): SampleResult = sample(LocalSearchParams())

    /** Lazily draw diverse samples. */
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())

    /** Lazily enumerate distinct models. */
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())

    /** Optimise against [objective] under the hard constraints. */
    fun minimize(objective: LinearObjective): MinimizeResult = minimize(objective, LocalSearchParams())
}
