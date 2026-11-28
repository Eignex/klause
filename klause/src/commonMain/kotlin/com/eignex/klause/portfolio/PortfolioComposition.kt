package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.SearchEvent

/** Whether the problem is a constraint optimization or a pure satisfaction problem. */
enum class Kind {
    /** Constraint optimization: the model carries an objective to minimise. */
    COP,

    /** Pure satisfaction: any feasible assignment, or a proof that none exists. */
    CSP,
}

/** Which engine family the portfolio draws arms from. */
enum class EngineMix {
    /** Local-search arms only. */
    LOCAL_SEARCH,

    /** Complete backtrack arms only. */
    BACKTRACK,

    /** Both: local search streams incumbents while backtrack tightens and proves the bound. */
    MIXED,

    /** Hybrid ALNS arms only: a destroy/repair loop with CP repair. */
    ALNS,
}

/**
 * A point in the portfolio configuration space: compute width ([cores]), pool size ([arms]), [kind] and
 * [engine].
 *
 * [cores] and [arms] are independent: a single core still draws on a multi-arm pool, and when
 * `arms < cores` [PortfolioBuilder.build] replicates the composed arms across the extra lanes with distinct
 * seeds.
 *
 * A curated single-core mixed optimization pool first searches with the default-sized pool. Additional
 * variants join after the first incumbent; an explicit pool uses every selected arm from the start.
 */
data class PortfolioScenario(
    /** Compute width. `1` selects the sequential executor; `> 1` the parallel one. */
    val cores: Int,
    /** Pool size. */
    val arms: Int,
    /** Whether the problem optimizes or only satisfies. */
    val kind: Kind,
    /** Which engine family supplies the arms. */
    val engine: EngineMix,
    /** Base RNG seed; each worker offsets it. */
    val seed: Long = 0L,
    /** Objective-shaping λ for the LS workers' optimize phase. */
    val lsLambda: Double = 1.0,
    /** The LP ceiling (`--lp`) each backtrack LP arm is capped under. `LpConfig.AGGRESSIVE` with no overrides
     *  leaves the arms uncapped. */
    val lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
    /** Entering-column policy used by every backtrack arm's zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    /** Source tolerance semantics for the arms' continuous completion; see [BacktrackParams.toleranceCheck]. */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    /** Optional override of the local-search arm pool as per-arm factories; `null` uses the curated pool. */
    val lsPool: List<() -> LocalSearchRecipe>? = null,
    /** Optional override of the backtrack arm pool as per-arm factories; `null` uses the curated pool. */
    val btPool: List<() -> BacktrackRecipe>? = null,
    /** Optional edit applied to the [BacktrackParams] every backtrack arm builds, curated or from [btPool]. It
     *  changes how each arm is built, never which arms the model offers. */
    val btEdit: ((BacktrackParams) -> BacktrackParams)? = null,
    /** Optional arm compiled from the model's `int_search(...)` annotations. A mixed roster seats it for
     *  selector diversity; a backtrack pool of ≥ 2 arms gives it the last slot, so the leading complete recipe
     *  keeps slot 0. Ignored when [btPool] overrides the pool. */
    val annotationArm: BacktrackParams? = null,
    /** Whether the backtrack arms share globally valid LP cuts through a [SharedCutPool]. Sound either way,
     *  since only global cuts cross arms. */
    val shareCuts: Boolean = true,
    /** LBD bound of the cross-arm glue-clause exchange filter. */
    val clauseShareMaxLbd: Int = 6,
    /** Length bound of the cross-arm glue-clause exchange filter. */
    val clauseShareMaxLen: Int = 12,
    /**
     * Work a sequential-portfolio arm's first segment may spend, in charged node-equivalents.
     *
     * A segment bounded by time pauses somewhere different on every run, so the search diverges between
     * invocations; bounding it by work makes a segment a function of the model and the seed alone. The
     * whole-solve deadline still applies.
     */
    val sliceWork: Long = DEFAULT_SLICE_WORK,
    /** Share of the arm bandit's evidence kept across the first incumbent. */
    val phaseRetention: Double = Portfolio.DEFAULT_PHASE_RETENTION,
    /**
     * Non-improving segments before an observable arm reseeds; scheduled restarting backtrack arms count
     * equivalent base work segments. Whole lanes count each positive-work return. Zero disables reseeding.
     * Complete search without a restart schedule retains its traversal across tighter incumbent cutoffs.
     */
    val reseedStaleThreshold: Int = 3,
    /** Optional solve-spanning decision-node allowance. Applied here so every arm that runs a backtrack engine
     *  spends the one counter, including arms that build their own [BacktrackParams] instead of drawing a
     *  [BacktrackRecipe] from a pool. */
    val nodeBudget: NodeBudget? = null,
    /** Collect the ordinary local-search arms' best committed residuals by factor kind. */
    val reportLsResiduals: Boolean = false,
    /** Record the ordinary local-search arms' active phase times. */
    val reportLsPhaseTimes: Boolean = false,
    /** Maximum node-equivalent allowance of a counted segment. */
    val maxSliceWork: Long = 150_000L,
    /** Geometric growth after an ordinary scheduled segment; preparation revisits keep the base allowance. */
    val sliceGrowth: Double = 1.5,
    /** Opt-in single-lane diagnostic allowance in charged node-equivalents; null uses elapsed policy. */
    val diagnosticWorkLimit: Long? = null,
    /** Trial the bounded curated core before heuristic probes after ordinary complete-worker coverage. */
    val earlyCoreAdmission: Boolean = false,
    /** Bounded private objective-core refinement. */
    val coreRefinement: CoreRefinementMode = CoreRefinementMode.NONE,
    /** Trial bounded general-integer/continuous companions; preserves explicit finite recipes. */
    val companionIntegers: Boolean = false,
    /** Defer numerical companion queries until a source incumbent exists, without changing admission. */
    val companionAfterIncumbent: Boolean = false,
    /** Per-companion active lifetime inside the containing solve deadline. */
    val companionActiveMillis: Long = 4_000,
    /** Stream physical phase/lifecycle events independently of work scheduling. */
    val reportPortfolioProgress: Boolean = false,
    /** Optional reward policy; source admission, complete-worker coverage and priors are unaffected. */
    val rewardPolicy: PortfolioRewardPolicy? = null,
    /** Whether the caller selected arm counts, including the ordinary default count. */
    val explicitArms: Boolean = false,
    /** Opt-in retained sharing-memory observation, independent of work scheduling. */
    val sharingMemory: Boolean = false,
    /** Maximum charged visits per sharing-memory observation. */
    val sharingMemoryWork: Long = 32_768L,
    /** Maximum deduplication entries across one sharing-memory observation. */
    val sharingMemoryScratch: Int = 4_096,
    /** Optional modeled byte limit per shared learned-clause pool. */
    val sharingMemoryBytes: Long? = null,
    /** Apply bounded root defaults to eligible curated satisfaction recipes. */
    val rootMaintenance: Boolean = true,
    /** Whether the backtrack arms' LP deductions accept float results within tolerances. */
    val numericalLp: Boolean = false,
    /** Gap within which the backtrack arms and the run end minimization; see [BacktrackParams.optimalityGap]. */
    val optimalityGap: OptimalityGap? = null,
) {
    init {
        require(sharingMemoryWork >= 0L && sharingMemoryScratch >= 0) { "sharing memory limits must be nonnegative" }
        require(sharingMemoryBytes == null || sharingMemoryBytes >= 0L) { "sharing memory bytes must be nonnegative" }
        require(cores >= 1) { "cores must be ≥ 1" }
        require(!earlyCoreAdmission ||
            (cores == 1 && kind == Kind.COP && engine == EngineMix.MIXED && btPool == null)) {
            "core-early requires a mixed optimization pool with automatic backtrack workers on one core"
        }
        require(diagnosticWorkLimit == null || (cores == 1 && diagnosticWorkLimit > 0L)) {
            "portfolio-work requires one core and a positive work limit"
        }
        require(arms >= 1) { "arms must be ≥ 1" }
        require(clauseShareMaxLbd >= 0 && clauseShareMaxLen >= 0) { "clause-share filter bounds must be ≥ 0" }
        require(reseedStaleThreshold >= 0) { "reseedStaleThreshold must be >= 0" }
        require(sliceWork > 0L && maxSliceWork >= sliceWork) { "invalid work slice bounds" }
        require(sliceGrowth.isFinite() && sliceGrowth >= 1.0) { "sliceGrowth must be finite and >= 1" }
        require(phaseRetention in 0.0..1.0) { "phaseRetention must be in [0, 1]" }
        require(companionActiveMillis in 1..60_000) { "companionActiveMillis must be in [1, 60000]" }
    }

    /** Factories for the two execution shapes a scenario can take. */
    companion object {
        /** Default arm-pool size, larger than one so a single core still schedules a real pool. */
        const val DEFAULT_ARMS = 6

        /** Default work in an arm's first segment; later segments grow. */
        const val DEFAULT_SLICE_WORK = 5_000L

        /** A parallel portfolio over [cores] cores; [arms] defaults to one arm per core. */
        fun parallel(cores: Int, kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L, arms: Int = cores) =
            PortfolioScenario(cores = cores, arms = arms, kind = kind, engine = engine, seed = seed)

        /** A single-core, bandit-scheduled portfolio over an [arms]-arm pool. */
        fun sequential(kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L, arms: Int = DEFAULT_ARMS) =
            PortfolioScenario(cores = 1, arms = arms, kind = kind, engine = engine, seed = seed)
    }
}

// A composed arm not yet materialised; each arm builds its own worker, so the builder needs no engine switch.
internal sealed interface WorkerConfig {
    fun withNodeBudget(budget: NodeBudget): WorkerConfig
    fun withCoreRefinement(mode: CoreRefinementMode): WorkerConfig = this
    fun withLsResiduals(): WorkerConfig = this
    fun withLsPhaseTimes(): WorkerConfig = this

    // The catalog label, before the engine prefix is added.
    val label: String

    // index is the arm's pool position and offsets the seed. armId is shared by replicas of one arm and is
    // attribution metadata only, never scheduling or seed input.
    fun materialize(
        problem: BakedProblem,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?,
    ): PortfolioWorker
}

internal class PortfolioArmPlan(
    val arms: List<WorkerConfig>,
    val firstSolutionCount: Int,
    val roles: List<ArmRole?>,
    val seats: List<String>,
)

// A mixed pool is a roster seated by role; a single-engine pool is the prefix of that engine's rank.
internal object PortfolioComposition {

    // The curated ranks keep only the arms the model offers the needs of. A pool the caller chose outright, an
    // injected one or a single-engine mix, is built as asked, so a model it cannot run is declined rather than
    // replaced.
    fun compose(
        scenario: PortfolioScenario,
        facts: ProblemFacts = ProblemFacts.assumed(scenario.kind),
    ): List<WorkerConfig> = seated(scenario, facts).map { it.config }

    // A curated sequential mixed optimization pool larger than the default searches for its first solution with
    // the default roster, which is its prefix.
    internal fun plan(scenario: PortfolioScenario, facts: ProblemFacts): PortfolioArmPlan {
        val seated = seated(scenario, facts)
        val deferred = scenario.cores == 1 && scenario.engine == EngineMix.MIXED && scenario.kind == Kind.COP &&
            scenario.lsPool == null && scenario.btPool == null
        val first = if (deferred) minOf(seated.size, PortfolioScenario.DEFAULT_ARMS) else seated.size
        return PortfolioArmPlan(seated.map { it.config }, first, seated.map { it.role }, seated.map { it.report })
    }

    private fun seated(scenario: PortfolioScenario, facts: ProblemFacts): List<SeatedArm> {
        val count = scenario.arms
        val arms = when (scenario.engine) {
            EngineMix.LOCAL_SEARCH ->
                lsArms(scenario.kind, count, scenario.lsPool, scenario.nodeBudget, facts).map(::declared)
            EngineMix.BACKTRACK -> btArms(scenario, count, facts).map(::declared)
            EngineMix.MIXED -> mixedArms(scenario, facts)
            EngineMix.ALNS -> alnsArms(count, scenario.nodeBudget).map(::declared)
        }
        val check = scenario.toleranceCheck
        return arms.map { arm ->
            val numerical = arm.config.let {
                if (scenario.numericalLp && it is BacktrackWorkerConfig) it.withNumericalLp() else it
            }.let {
                val gap = scenario.optimalityGap
                if (gap != null && it is BacktrackWorkerConfig) it.withOptimalityGap(gap) else it
            }
            val checked = if (check == null) numerical else when (numerical) {
                is BacktrackWorkerConfig -> numerical.withToleranceCheck(check)
                is LocalSearchWorkerConfig -> numerical.withToleranceCheck(check)
                is AlnsWorkerConfig -> numerical.withToleranceCheck(check)
                else -> numerical
            }
            SeatedArm(checked, arm.role, arm.needed)
        }
    }

    private fun declared(config: WorkerConfig): SeatedArm = SeatedArm(config, declaredRole(config))

    // The role a config's catalog arm declares; a recipe the catalogs do not hold declares none.
    private fun declaredRole(config: WorkerConfig): ArmRole? = when (config) {
        is BacktrackWorkerConfig ->
            if (config.label == ANNOTATION) ArmRole.SelectorDiversity else BacktrackCatalog.armOf(config.label)?.role
        is LocalSearchWorkerConfig -> LocalSearchCatalog.armOf(config.label)?.role
        is AlnsWorkerConfig -> ArmRole.NeighbourhoodImprovement
        is CoreGuidedWorkerConfig -> ArmRole.ObjectiveCores
    }

    private fun alnsArms(count: Int, nodeBudget: NodeBudget?): List<WorkerConfig> =
        AlnsWorkerConfig.diverse(count, nodeBudget)

    private fun lsArms(
        kind: Kind,
        count: Int,
        pool: List<() -> LocalSearchRecipe>?,
        nodeBudget: NodeBudget?,
        facts: ProblemFacts,
    ): List<WorkerConfig> = if (pool == null) {
        LocalSearchWorkerConfig.diverse(kind, count, nodeBudget, facts.profile.problemClass)
    } else {
        List(count) { LocalSearchWorkerConfig(pool[it % pool.size](), nodeBudget) }
    }

    // The node budget is applied to the composed pool so that capping a run does not change which pool it composes.
    private fun btArms(scenario: PortfolioScenario, count: Int, facts: ProblemFacts): List<BacktrackWorkerConfig> {
        val btPool = scenario.btPool
        if (btPool != null) return List(count) { injected(scenario, btPool[it % btPool.size]) }
        val base = BacktrackWorkerConfig.diverse(
            scenario.kind,
            count,
            scenario.lpCeiling,
            scenario.nodeBudget,
            scenario.zeroObjectivePricing,
            facts,
            curatedEdit(scenario, facts),
        )
        val annotation = annotation(scenario)
        return if (annotation == null || count < 2) base else base.dropLast(1) + annotation
    }

    // The `--lp` ceiling caps an injected pool too, or `--lp` would be silently ignored whenever the caller
    // names its arms.
    private fun injected(scenario: PortfolioScenario, recipe: () -> BacktrackRecipe) = BacktrackWorkerConfig(
        recipe().editing(scenario.btEdit).capLp(scenario.lpCeiling).spending(scenario.nodeBudget),
        scenario.zeroObjectivePricing,
    )

    private fun annotation(scenario: PortfolioScenario): BacktrackWorkerConfig? {
        val params = scenario.annotationArm ?: return null
        return BacktrackWorkerConfig(
            BacktrackWorkerConfig.ofParams(ANNOTATION, params.copy(nodeBudget = scenario.nodeBudget)),
            scenario.zeroObjectivePricing,
        )
    }

    // The caller's btEdit applies after the automatic satisfaction defaults.
    private fun curatedEdit(scenario: PortfolioScenario, facts: ProblemFacts): ((BacktrackParams) -> BacktrackParams)? {
        val edit = scenario.btEdit
        if (scenario.kind != Kind.CSP) return edit
        val clausal = facts.profile.problemClass == ProblemClass.Sat
        val boundedRoot = scenario.rootMaintenance && scenario.engine == EngineMix.BACKTRACK &&
            scenario.annotationArm == null &&
            (clausal || facts.profile.problemClass == ProblemClass.PseudoBoolean) && !facts.profile.optimizing
        // Root cut harvesting spends a satisfaction arm's first slice before node refutation.
        return { params ->
            val rootParams = if (params.lpConfig == null) {
                params
            } else {
                params.copy(lpPlan = params.lpPlan.copy(rootCutHarvest = false))
            }
            val defaults = if (boundedRoot) {
                rootParams.boundedRootMaintenance(
                    warmupWork = if (clausal) 4096L else 0L,
                    clausal = clausal,
                )
            } else {
                rootParams
            }
            edit?.invoke(defaults) ?: defaults
        }
    }

    private fun BacktrackParams.boundedRootMaintenance(
        warmupWork: Long,
        clausal: Boolean,
    ): BacktrackParams = copy(
        rootSimplification = true,
        rootSimplifyBatch = 256,
        rootProbeBatch = 2,
        rootProbeWork = 8192L,
        rootProbeImplications = false,
        rootProbeBinaryGate = clausal,
        rootProbeGeneralGate = !clausal,
        inprocessingWork = if (clausal) 4096L else 16384L,
        inprocessingRootWork = 256L,
        inprocessingProbeWork = if (clausal) 3072L else 12288L,
        inprocessingSubsumeWork = if (clausal) 0L else inprocessingSubsumeWork,
        inprocessingVivifyWork = if (clausal) 0L else inprocessingVivifyWork,
        inprocessingProbeInterval = 32768L,
        inprocessingInterval = 32L,
        inprocessingGrowth = 8,
        inprocessingCooldown = 32L,
        inprocessingWarmupWork = warmupWork,
    )

    // An optimization reads local search first, where incumbents drive the bound, and a satisfaction model
    // complete search first, which only it can refute.
    private fun mixedArms(scenario: PortfolioScenario, facts: ProblemFacts): List<SeatedArm> {
        val local = localRank(scenario, facts)
        val complete = completeRank(scenario, facts)
        val ranks = when (scenario.kind) {
            Kind.COP -> listOfNotNull(local, complete, neighbourhoodRank(scenario, facts))
            Kind.CSP -> listOf(complete, local)
        }
        // A pinned arm warms in the order its role would: complete search first on an optimization.
        val lead = if (scenario.kind == Kind.COP) listOf(complete, local) else listOf(local, complete)
        val annotated = scenario.annotationArm != null && scenario.btPool == null
        val relaxation = complete.arms.any { it.role == ArmRole.LpBounding }
        val needed = ArmRole.needed(scenario.kind, facts, annotated, relaxation)
        return PortfolioRoster.seat(needed, ranks, scenario.arms, lead)
    }

    private fun localRank(scenario: PortfolioScenario, facts: ProblemFacts): RosterRank {
        val pool = scenario.lsPool
        val budget = scenario.nodeBudget
        if (pool != null) {
            return RosterRank(pool.map { recipe ->
                RosterCandidate(LocalSearchCatalog.armOf(recipe().label)?.role) {
                    LocalSearchWorkerConfig(recipe(), budget)
                }
            }, pinned = true)
        }
        return RosterRank(LocalSearchCatalog.rankedArms(scenario.kind, facts.profile).map { arm ->
            RosterCandidate(arm.role) { LocalSearchWorkerConfig(LocalSearchCatalog.recipe(arm), budget) }
        })
    }

    // The model's own search order leads the complete rank. It and the core worker hold private state, so
    // neither repeats on a fresh seed.
    private fun completeRank(scenario: PortfolioScenario, facts: ProblemFacts): RosterRank {
        val pool = scenario.btPool
        if (pool != null) {
            return RosterRank(pool.map { recipe ->
                RosterCandidate(BacktrackCatalog.armOf(recipe().label)?.role) { injected(scenario, recipe) }
            }, pinned = true)
        }
        val edit = curatedEdit(scenario, facts)
        val catalog = BacktrackCatalog.rankedArms(scenario.kind, facts).map { arm ->
            arm to if (arm == BacktrackArm.CoreGuided) {
                RosterCandidate(arm.role) { CoreGuidedWorkerConfig(scenario.nodeBudget, edit = scenario.btEdit) }
            } else {
                RosterCandidate(arm.role) {
                    BacktrackWorkerConfig(
                        BacktrackCatalog.recipe(arm).editing(edit).capLp(scenario.lpCeiling)
                            .spending(scenario.nodeBudget),
                        scenario.zeroObjectivePricing,
                    )
                }
            }
        }
        val source = annotation(scenario)?.let { RosterCandidate(ArmRole.SelectorDiversity) { it } }
        return RosterRank(
            listOfNotNull(source) + catalog.map { it.second },
            repeating = catalog.filter { it.first != BacktrackArm.CoreGuided }.map { it.second },
        )
    }

    // Hybrid ALNS with CP repair works around an incumbent, so a satisfaction model has nothing for it, and a
    // pool with a core per arm keeps its cores.
    private fun neighbourhoodRank(scenario: PortfolioScenario, facts: ProblemFacts): RosterRank? {
        if (scenario.cores >= scenario.arms || !facts.offersAll(AlnsWorkerConfig.NEEDS)) return null
        val arm = RosterCandidate(ArmRole.NeighbourhoodImprovement) {
            AlnsWorkerConfig(nodeBudget = scenario.nodeBudget)
        }
        return RosterRank(listOf(arm), repeating = emptyList())
    }

    private const val ANNOTATION = "annotation"
}
