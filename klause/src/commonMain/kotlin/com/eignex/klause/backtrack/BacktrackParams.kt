package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.IndomainSet
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.hasDefaultNumericalSettings
import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PROPAGATION_CANCEL_FLOOR
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.forInvocation
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.withLifetime
import com.eignex.klause.util.ExecutionControl

/**
 * Per-call params for [BacktrackSolver].
 *
 *  - [randomSeed] — seeds the engine RNG used by [variableSelector] and [valueSelector]; `null` picks a fresh
 *    seed per call.
 *  - [assumptions] — variables pinned for the duration of the call.
 *  - [variableSelector] — picks the next variable to branch on. Defaults to [Vsids]; alternatives include
 *    [SmallestDomain] (first-fail), [InputOrder], and [RandomVariable].
 *  - [valueSelector] — orders the values of the chosen variable. Defaults to [IndomainRandom]; alternatives
 *    include [IndomainMin] / [IndomainMax] / [IndomainMiddle] / [IndomainSet] for hole domains.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity filter for [BacktrackSolver.enumerate] that skips
 *    models within `minHammingDistance` of a recently yielded model. Ignored by `solve` / `samples`.
 */
data class BacktrackParams(
    /**
     * Additional theory or lemma components for this search.
     *
     * The factory runs once per shared search run, outside the decision hot path. Components observe the
     * shared trail through [com.eignex.klause.solver.search.SearchSession] and must not read CP domains.
     */
    val componentFactory: (() -> List<SearchComponent>)? = null,
    /**
     * Decisions one slice of the search may push before it gives up (Unknown). A restart, a resuming
     * portfolio arm or an ALNS repair re-enters the engine with the allowance renewed, so this bounds a
     * slice and not a solve; [nodeBudget] bounds the whole solve.
     */
    val maxDecisions: Long = Long.MAX_VALUE,
    /**
     * Wall-clock-independent operation budget. For [BacktrackSolver] one instruction is one decision attempt,
     * the unit [maxDecisions] counts; when both are set, the smaller wins. `null` means no cap.
     */
    val maxInstructions: Long? = null,
    /**
     * Solve-spanning cap on decision nodes; see [NodeBudget]. Enforced through [cancellation], the same
     * cooperative path a deadline uses. `null` (default) leaves no solve-wide cap.
     */
    val nodeBudget: NodeBudget? = null,
    val randomSeed: Long? = null,
    override val assumptions: Assumptions = Assumptions.None,
    val variableSelector: VariableSelector = Vsids(),
    val valueSelector: ValueSelector = IndomainRandom,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    /**
     * Luby restart base: when non-null, the search restarts from level 0 after
     * `lubyN(restartIdx) * lubyRestartBase` decisions. Saved phases and the branch-and-bound bound survive
     * the restart. Must be positive; `null` (default) disables Luby restarts
     * (M. Luby, A. Sinclair, D. Zuckerman, "Optimal speedup of Las Vegas algorithms", Inf. Process. Lett. 47(4), 1993,
     * https://doi.org/10.1016/0020-0190(93)90029-9).
     */
    val lubyRestartBase: Long? = null,
    /**
     * LBD-adaptive restarts: a short window of recent LBD running hotter than the long-run average forces a
     * restart, and trail-size blocking suppresses it while the search drives deep toward a model. See
     * [GlucoseRestart]. When on, [lubyRestartBase] is ignored. Disabled by default.
     */
    val adaptiveRestart: Boolean = false,
    /**
     * The exponential-moving-average variant of [adaptiveRestart]: a fast LBD average against a slow one, with
     * the same trail-size blocking. See [EmaRestart]. Takes precedence over [adaptiveRestart] and
     * [lubyRestartBase]. Disabled by default.
     */
    val emaRestart: Boolean = false,
    /**
     * Stable/focused mode-switching restarts: no restarts until a first feasible solution, then a stable mode
     * (sparse Luby restarts, diving on the saved phase) alternating with focused modes (frequent adaptive
     * restarts). See [ModeSwitchingRestartSchedule]. Takes precedence over [emaRestart], [adaptiveRestart], and
     * [lubyRestartBase]. Disabled by default.
     */
    val modeSwitchingRestart: Boolean = false,
    /**
     * Phase saving: a fresh descent tries each variable's last committed value first
     * (K. Pipatsrisawat, A. Darwiche, "A lightweight component caching scheme for
     * satisfiability solvers", SAT 2007, https://doi.org/10.1007/978-3-540-72788-0_28). Disabled by default.
     */
    val phaseSaving: Boolean = false,
    /**
     * Target phasing and rephasing on top of [phaseSaving]: fresh Boolean decisions lean toward the deepest
     * conflict-free Boolean assignment seen so far, and every [rephaseInterval] conflicts the polarity source
     * rotates (target, saved, all-true, all-false, random) to escape basins the saved phase keeps reproducing.
     * Integer value selection is untouched. Disabled by default.
     */
    val targetPhasing: Boolean = false,
    /** Conflicts between rephasing rotations when [targetPhasing] is on. Must be positive. */
    val rephaseInterval: Long = 1000L,
    /**
     * Solution-based phasing: each new incumbent seeds the phase source with its Boolean polarities and integer
     * values, so the stable phase and the `SOLUTION` rephase mode dive toward it. Disabled by default.
     */
    val solutionPhasing: Boolean = false,
    /**
     * Cap on the learned-clause database size. Once exceeded, a forgetting pass at each restart keeps permanent
     * clauses and glue clauses (LBD ≤ [lbdGlueThreshold]) and the lowest-LBD rest up to the cap (G. Audemard,
     * L. Simon, "Predicting learnt clauses quality in modern SAT solvers", IJCAI 2009,
     * https://www.ijcai.org/Proceedings/09/Papers/074.pdf). `null` (default) disables forgetting.
     */
    val maxLearnedClauses: Int? = null,
    /** Optional modeled retained-byte soft limit; reclamation also runs without restarts. */
    val learnedMemoryBytes: Long? = null,
    /** Preparation and atomic publication allowance for one retained-memory attempt. */
    val learnedMemoryWork: Long = 65_536L,
    /** Maximum modeled scratch bytes for retained-memory preparation and publication. */
    val learnedMemoryScratchBytes: Long = 1_048_576L,
    /** Glue threshold for [maxLearnedClauses]: clauses with LBD ≤ this are always retained. */
    val lbdGlueThreshold: Int = 2,
    /**
     * Three-tier learned-clause database, when [maxLearnedClauses] is set: a permanent core
     * (LBD ≤ [lbdGlueThreshold]), a mid tier (LBD ≤ [midLbdThreshold]) demoted when idle across a reduction, and
     * a local tier deleted aggressively. Clauses used in a later conflict are promoted on the next reduction.
     * Disabled by default.
     */
    val tieredLearnedDb: Boolean = false,
    /**
     * Mid-tier LBD threshold for [tieredLearnedDb]: a learned clause with
     * `lbdGlueThreshold < LBD ≤ midLbdThreshold` starts in the mid tier, higher LBD in the local tier.
     */
    val midLbdThreshold: Int = 6,
    /**
     * Clause vivification at restart boundaries: walks a bounded slice of the learned-clause database and
     * shortens each clause by asserting the negations of its literals under propagation
     * (C. Piette, Y. Hamadi, L. Saïs, "Vivifying propositional clausal formulae",
     * ECAI 2008, https://doi.org/10.3233/978-1-58603-891-5-525). Atom-literal clauses are skipped. Disabled by
     * default; only honoured when [assumptions] is empty.
     */
    val vivification: Boolean = false,
    /**
     * Learned clauses vivified per restart when [vivification] is on. A round-robin cursor covers the whole
     * database over successive restarts. Must be positive.
     */
    val vivifyBatch: Int = 256,
    /**
     * Subsumption and self-subsuming resolution over a bounded slice of the learned-clause database at restart
     * boundaries. Atom-literal clauses are skipped. Disabled by default; only honoured when [assumptions] is
     * empty.
     */
    val subsumption: Boolean = false,
    /** Learned clauses checked per scheduled run when [subsumption] is on. Must be positive. */
    val subsumeBatch: Int = 1024,
    /**
     * Run the scheduled inprocessing loop every Nth restart, so restart-heavy configurations pay a fraction of
     * the per-restart cost. Must be positive.
     */
    val inprocessingCadence: Int = 1,
    /** Simplify Boolean clauses against unconditional facts at safe complete roots. */
    val rootSimplification: Boolean = false,
    /** Maximum source and learned clauses examined by one root simplification slice. */
    val rootSimplifyBatch: Int = 256,
    /** Maximum failed-literal probes per scheduled root slice; zero disables probing. */
    val rootProbeBatch: Int = 0,
    /** Propagation work ceiling per root probe, in addition to the containing invocation token. */
    val rootProbeWork: Long = 8192L,
    /** Work ceiling for a complete scheduled inprocessing slice; zero withholds every pass. */
    val inprocessingWork: Long = Long.MAX_VALUE,
    /** Cheap root scan allowance within [inprocessingWork]. */
    val inprocessingRootWork: Long = Long.MAX_VALUE,
    /** Failed-literal probing allowance within [inprocessingWork]. */
    val inprocessingProbeWork: Long = Long.MAX_VALUE,
    /** Subsumption preparation, matching and mutation allowance within [inprocessingWork]. */
    val inprocessingSubsumeWork: Long = Long.MAX_VALUE,
    /** Vivification scanning, propagation and mutation allowance within [inprocessingWork]. */
    val inprocessingVivifyWork: Long = Long.MAX_VALUE,
    /** Non-inprocessing propagation work between safe-root opportunities; zero disables this trigger. */
    val inprocessingInterval: Long = 0L,
    /** Learned-clause growth triggering work at a safe root; zero disables this trigger. */
    val inprocessingGrowth: Int = 0,
    /** Non-inprocessing propagation work required after any scheduled attempt. */
    val inprocessingCooldown: Long = 0L,
    /**
     * Shared objective upper bound for branch-and-bound minimization: the search prunes a subtree whose linear
     * objective lower bound is `≥ supplier()`, alongside its own incumbent. Ignored for non-linear objectives,
     * which yield no sound lower bound. `null` (default) disables external bound sharing.
     */
    val objectiveBoundSupplier: (() -> Double)? = null,
    /**
     * Sink for objective lower bounds this worker proves, such as the root LP relaxation bound. Every published
     * value is a sound lower bound on the optimum. `null` (default) disables lower-bound sharing.
     */
    val objectiveLowerBoundSink: ((Double) -> Unit)? = null,
    /**
     * Shared objective lower bound. Before each search slice the engine tightens its single ascending objective
     * variable to `⌈supplier()⌉`, which removes no solution. `null` (default) disables it; a no-op without a
     * single ascending objective variable.
     */
    val objectiveLowerBoundSupplier: (() -> Double)? = null,
    /** Sink for each improving incumbent this worker finds, with its objective. `null` (default) disables it. */
    val improvedSolutionSink: ((Sample, Double) -> Unit)? = null,
    /**
     * Verified incumbents published by other workers. On each restart the engine adopts an unseen incumbent as
     * solution-phasing hints (see [solutionPhasing]); value hints reorder trials (out-of-domain integers are
     * clamped) without pruning authority. Non-restarting retained optimization with [objectiveBoundSupplier]
     * adopts the current assignment at its first root, once it satisfies this run's pins, as a starting proof
     * bound, without priming guidance or earning progress. After its first incumbent, [SolutionGuided] value
     * selection adopts better pooled incumbents when optimization resumes. LP-bounding search may prefer those
     * values before its first local witness. `null` (default) disables it.
     */
    val pooledIncumbents: IncumbentSource<Sample, Double>? = null,
    /**
     * Sink for level-0 integer bound tightenings `(varId, lo, hi)` that hold at every solution. Incumbent-relative
     * reduced-cost fixings are not published. `null` (default) disables it.
     */
    val globalVarBoundSink: ((varId: Int, lo: Long, hi: Long) -> Unit)? = null,
    /**
     * Shared globally valid lower bound per integer variable, imported at level 0. `Int.MIN_VALUE` /
     * `Int.MAX_VALUE` mean nothing is shared. `null` (default) disables import.
     */
    val globalVarLowerSupplier: ((varId: Int) -> Long)? = null,
    /** Companion of [globalVarLowerSupplier] for the shared upper bounds. */
    val globalVarUpperSupplier: ((varId: Int) -> Long)? = null,
    /**
     * Told each time an imported shared bound tightened a domain here, `lower` naming the side, so a portfolio
     * can credit the arm that proved it. `null` (default) reports nothing.
     */
    val globalVarImportSink: ((varId: Int, lower: Boolean) -> Unit)? = null,
    /**
     * Told the nanoseconds each exchange of the objective floor or shared variable bounds took, by channel.
     * `null` (default) measures nothing.
     */
    val sharingTimer: ((channel: SharingChannel, nanos: Long) -> Unit)? = null,
    /**
     * Emphasis-driven LP-relaxation selector: an [LpEmphasis] cost ceiling plus per-technique overrides, resolved
     * against the problem's structure by [LpAutoConfig.resolve]. Resolved flags are OR-ed onto [lpPlan], so an
     * explicit flag is never turned off. `null` (default) uses [lpPlan] verbatim.
     */
    val lpConfig: LpConfig? = null,
    /** Per-technique LP-relaxation plan; the default is all off. */
    val lpPlan: LpPlan = LpPlan(),
    /** Use reliable, recent directional LP gains for finite branching; missing history keeps ordinary LP guidance. */
    val lpDirectionalGains: Boolean = false,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    /**
     * Tolerance semantics for continuous leaves: a float LP optimum is accepted without exact certification if
     * this check accepts the completed sample, and an incumbent without exact reals is admitted when it passes
     * the same check. A captured source policy can still require exact optimum certification. Exact repair may
     * publish a source-accepted improving primal while leaving the leaf unresolved; that grants no completion
     * certificate or exact coordinates. `null` keeps exact semantics.
     */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    /**
     * Gap within which minimization ends: nodes are pruned against its cutoff of the incumbent, and an exhausted
     * search reports the incumbent optimal with the bound it proved
     * ([com.eignex.klause.solver.result.MinimizeResult.Optimal.gapBound]). `null` closes the bound completely. A
     * repairing search ignores it, and a problem whose source declares an exact tolerance rejects it.
     */
    val optimalityGap: OptimalityGap? = null,
    /** Cooperative cancellation predicate; see [ExecutionControl]. */
    val cancellation: ExecutionControl = ExecutionControl.Never,
    /**
     * Per-call fire floor before propagation polls [cancellation]; see [PROPAGATION_CANCEL_FLOOR]. Overridable so a
     * test can force the runaway-fixpoint deadline path deterministically.
     */
    val propagationCancelFloor: Int = PROPAGATION_CANCEL_FLOOR,
    /**
     * Total wall-clock solve budget in milliseconds, when the caller knows it. Advisory: [cancellation] is the
     * hard stop; this sizes the LP subsystem's wall-clock sub-budgets. `null` (default) leaves those caps off.
     */
    val solveBudgetMillis: Long? = null,
    /**
     * Live-event listener; see [SearchEvent]. Called inline on the search thread at coarse points only
     * (restarts, learned-DB sweeps, incumbents). `null` (default) disables observation.
     */
    val onEvent: ((SearchEvent) -> Unit)? = null,
    /**
     * Learned-clause exchange invoked at each restart boundary to import other workers' nogoods and export this
     * search's glue clauses; see [ClauseExchange]. All participants must be built from the same problem. `null`
     * (default) means no sharing.
     */
    val clauseExchange: ClauseExchange? = null,
    /**
     * Globally valid LP cut exchange invoked at each restart boundary; see [CutExchange]. Like [clauseExchange] it
     * requires every participant to be built from the same problem. `null` (default) means no cut sharing.
     */
    val cutExchange: CutExchange? = null,
    /**
     * Whether to route an eligible pure-Boolean problem through the native-SAT lane (arena-packed
     * two-watched-literal propagation). `null` (default) selects it exactly when the propagation projection is
     * eligible; `true` requests it (a no-op on an ineligible problem); `false` forces the general path.
     */
    val nativeSat: Boolean? = null,
    /**
     * Whether to learn pseudo-Boolean cutting-planes constraints from conflicts over pseudo-Boolean / cardinality
     * constraints, by division-based resolution with a clause fallback. `null` (default) enables it on
     * pure-Boolean problems, where cutting planes refute counting structure that resolution cannot (A. Haken,
     * "The intractability of resolution", Theor. Comput. Sci. 39, 1985,
     * https://doi.org/10.1016/0304-3975(85)90144-6). Explicit enablement also admits Boolean-only conflicts in
     * mixed models; an integer atom in any reached reason keeps the clause fallback.
     */
    val pbLearning: Boolean? = null,
    /** Learn exact mandatory integer rows from finite conflicts; disabled by default. */
    val integerRowLearning: Boolean = false,
    /**
     * Enforce the incumbent as a refutable pseudo-Boolean cutoff for a pure-Boolean weighted objective: each
     * incumbent `K` posts `Σ boolWeights·x ≤ K − 1` at the root, so cutting-planes conflict analysis can refute it
     * to prove optimality. Ignored when the objective is a single integer variable. Disabled by default because
     * the cutoff constrains the search for improving incumbents.
     */
    val pbObjectiveCutoff: Boolean = false,
    /**
     * Objective-guided value selection on the minimization path: try each variable's objective-improving value
     * first (the cost-minimizing polarity or domain end), replacing [valueSelector]. Disabled by default because
     * a uniform default would collapse value-order diversity across portfolio arms.
     */
    val objectiveGuidedValues: Boolean = false,
    /** Collect bounded factor-family propagation, reason and learned-use diagnostics. */
    val propagationAttribution: Boolean = false,
    /** Retry bounded historical Table reasons against the minimized conflict frontier. */
    val historicalTableReasons: Boolean = false,
    /** Prefer lower measured family work within a bounded, starvation-protected queue prefix. */
    val costAwarePropagation: Boolean = false,
    /** Check recorded redundancy failures before reconstructing sibling historical reasons. */
    val reasonPreflight: Boolean = false,
    /** Bounded propositional rebuild diagnostics within root simplification; zero disables capture. */
    val rootRebuildCaptureVisits: Int = 0,
    /**
     * Private Boolean work: zero disables, positive values bound manual trials, and -1 selects
     * automatic parity admission within a 4,194,304-work grant.
     */
    val privateRebuildWork: Long = 0L,
    /**
     * Inline diagnostic observer for attempted shared decisions and accepted decision commits.
     * Attempts precede propagation; commits follow a consistent push and successful decision accounting.
     * The caller owns its scope, bounds and retention; callbacks must not mutate the search context.
     */
    val branchObserver: SearchBranchAttemptObserver? = null,
    /** Non-inprocessing work after the first eligible complete root before optional slices; zero disables warmup. */
    val inprocessingWarmupWork: Long = 0L,
    /** Private modeled-byte grant; null shares [learnedMemoryScratchBytes]. */
    val privateRebuildBytes: Long? = null,
    /** Capture conditional binaries from completed consistent root probes; failed-literal units remain enabled. */
    val rootProbeImplications: Boolean = true,
    /** Non-inprocessing work between optional probing slices; zero follows the shared root trigger. */
    val inprocessingProbeInterval: Long = 0L,
    /** Limit Native root probes to learned binaries until a root fact or known binary permits propagation. */
    val rootProbeBinaryGate: Boolean = false,
    /** Explore one global batch, then prefer learned neighborhoods in Boolean General states without root signals. */
    val rootProbeGeneralGate: Boolean = false,
    /** Per-conflict work allowance for optional cutting-planes learning, inside propagation work. */
    val arithmeticLearningWork: Long = 262_144,
    /** Per-conflict cumulative modeled allocation allowance for optional cutting-planes learning. */
    val arithmeticLearningAllocationBytes: Long = 8L * 1024 * 1024,
    /** Apply reliable directional branching advice; false retains its observations, collection and comparison work. */
    val lpDirectionalAdvice: Boolean = true,
    /** Admit derived integer rows; disabling keeps derivation and integer decision naming enabled. */
    val integerRowAdmission: Boolean = true,
    /** Allow retained rows to serve as arithmetic seeds and endpoint reasons. */
    val integerRowReuse: Boolean = true,
) : SolverParams {
    init {
        require(arithmeticLearningWork >= 0L && arithmeticLearningAllocationBytes >= 0L)
        require(rootRebuildCaptureVisits in 0..1_048_576) { "rebuild capture visits must be in 0..1048576" }
        require(privateRebuildWork in -1L..1_048_576L) {
            "private rebuild work must be -1 (automatic) or in 0..1048576"
        }
        require(privateRebuildBytes == null || privateRebuildBytes >= 0L)
        require(learnedMemoryBytes == null || learnedMemoryBytes >= 0L)
        require(learnedMemoryWork >= 0L && learnedMemoryScratchBytes >= 0L)
        require(rootSimplifyBatch > 0) { "root simplification batch must be positive" }
        require(rootProbeBatch >= 0) { "root probe batch must be nonnegative" }
        require(rootProbeWork > 0L) { "root probe work must be positive" }
        require(listOf(inprocessingWork, inprocessingRootWork, inprocessingProbeWork,
            inprocessingSubsumeWork, inprocessingVivifyWork, inprocessingInterval,
            inprocessingCooldown, inprocessingWarmupWork, inprocessingProbeInterval).all { it >= 0L }) {
            "inprocessing work limits must be nonnegative"
        }
        require(inprocessingGrowth >= 0) { "inprocessing growth must be nonnegative" }
        require(lubyRestartBase == null || lubyRestartBase > 0L) {
            "lubyRestartBase must be positive or null (off)"
        }
    }

    override fun withAssumptions(assumptions: Assumptions): BacktrackParams =
        if (assumptions.isEmpty && assumptions.deductions.isEmpty) this
        else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: ExecutionControl): BacktrackParams =
        copy(cancellation = this.cancellation.forInvocation(cancellation))

    override fun withLifetimeCancellation(cancellation: ExecutionControl): BacktrackParams =
        copy(cancellation = this.cancellation.withLifetime(cancellation))

    /** The LP-bounding runtime's slice of these params (see [LpParams]) — what `LpEngine` consumes. */
    fun lpParams(): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing)

    private companion object {
        private fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}

// Additional native scheduling bounds can disrupt LP branch guidance.
internal val BacktrackParams.useCumulativeUnarySubsets: Boolean
    get() = lpPlan.copy(branching = true, rootCutHarvest = true).hasDefaultNumericalSettings() &&
        lpConfig.let { config ->
            config == null || (config.emphasis == LpEmphasis.OFF && config.overrides.values.none { it })
        }
