package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import kotlin.math.roundToInt

/** Whether the problem is a constraint *optimization* (an objective to minimise) or a pure
 *  *satisfaction* problem. One of the three portfolio axes; drives which arms are eligible and how
 *  a mixed pool is split. */
enum class Kind {
    /** Constraint optimization — the model carries an objective to minimise. */
    COP,

    /** Pure satisfaction — no objective; the goal is any-feasible (or proving UNSAT). */
    CSP,
}

/** Which engine family the portfolio draws arms from — the second portfolio axis. */
enum class EngineMix {
    /** Local-search arms only (no complete search, no CP dependency). */
    LOCAL_SEARCH,

    /** Complete backtrack arms only (proofs, optima). */
    BACKTRACK,

    /** Both — LS streams incumbents while backtrack tightens/proves the bound. */
    MIXED,

    /** Hybrid ALNS arms only — a large-neighbourhood destroy/repair loop with CP repair. */
    ALNS,
}

/**
 * A point in the portfolio configuration space. Its axes are **cores** (compute width) and **arms**
 * (pool size) — kept separate so they don't conflate — plus **kind** (COP vs CSP) and
 * **engine** (LS / backtrack / mixed). [PortfolioComposition.compose] turns a scenario into an ordered
 * arm list of size [arms], and [PortfolioBuilder.build] materialises it into runnable
 * [PortfolioWorker]s — so every scenario flows through one construction path.
 *
 * [cores] is the number of lanes a [Portfolio] schedules the [arms] arms on, one segment at a time per lane.
 * [arms] and [cores] are fully independent: a single core still draws on a multi-arm pool, a parallel run may
 * carry more arms than cores, and — when
 * `arms < cores` — [PortfolioBuilder.build] replicates the composed arms across the extra lanes with
 * distinct seeds, so a parallel run can be wider than its pool of distinct configs.
 *
 * A curated single-core mixed optimization pool first searches with the default-sized pool. Additional
 * variants join after the first incumbent; an explicit pool uses every selected arm from the start.
 */
data class PortfolioScenario(
    /** Compute width. `1` selects the single-core sequential executor; `> 1` the parallel one. */
    val cores: Int,
    /** Pool size — how many distinct arms [PortfolioComposition.compose] produces. Independent of
     *  [cores]: when `arms < cores` a parallel track replicates the composed arms across the extra
     *  lanes (with distinct seeds); when `arms >= cores` every lane is a distinct composed arm. */
    val arms: Int,
    /** Whether the problem optimizes (COP) or only satisfies (CSP). */
    val kind: Kind,
    /** Which engine family supplies the arms (LS / backtrack / mixed). */
    val engine: EngineMix,
    /** Base RNG seed; worker `i` offsets it so the pool explores distinct trajectories. */
    val seed: Long = 0L,
    /** Objective-shaping λ for the LS workers' optimize phase (mirrors the CLI's CBLS λ=1.0). */
    val lsLambda: Double = 1.0,
    /** The LP ceiling for the backtrack arms (the `--lp` parameter): each LP arm is capped under
     *  this — its emphasis lowered to the ceiling's and the ceiling's per-technique overrides applied.
     *  `LpConfig.AGGRESSIVE` (default, no overrides) leaves the arms uncapped — the pool spreads the
     *  LP-intensity itself; an `OFF` emphasis disables LP, and overrides force individual techniques. */
    val lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
    /** Entering-column policy used by every backtrack arm's zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    /** Tolerance semantics for every backtrack arm's continuous leaves; see [BacktrackParams.toleranceCheck]. */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    /** Optional override of the local-search arm pool — per-arm factories (a fresh recipe per slot).
     *  `null` uses the curated `LocalSearchCatalog` pool unchanged; a non-null pool is the CLI's resolved
     *  recipes (a named base, or the curated pool with axis edits applied). */
    val lsPool: List<() -> LocalSearchRecipe>? = null,
    /** Optional override of the backtrack arm pool — per-arm [BacktrackRecipe] factories (a fresh
     *  recipe per slot, wrapping past the pool size), the exact backtrack analogue of [lsPool]. `null`
     *  uses the curated [BacktrackWorkerConfig] pool. */
    val btPool: List<() -> BacktrackRecipe>? = null,
    /** Optional edit applied to the [BacktrackParams] every backtrack arm builds, curated or from [btPool]. It
     *  changes how each arm is built, never which arms the model offers, so the curated pool keeps its
     *  applicability filter under an override. */
    val btEdit: ((BacktrackParams) -> BacktrackParams)? = null,
    /** Optional model search-annotation arm: the [BacktrackParams] compiled from the model's
     *  `int_search(...)` annotations. When present (and the pool carries ≥ 2 backtrack arms), it takes
     *  the last backtrack slot so the free CP portfolio also follows the model's own search order,
     *  while the `satOptimized` guard keeps slot 0. Ignored when [btPool] overrides the pool. */
    val annotationArm: BacktrackParams? = null,
    /** Whether the backtrack arms share globally-valid LP cuts through a [SharedCutPool], the
     *  cut analogue of the always-on learned-clause pool. On by default; sound either way (only global
     *  cuts cross arms, so it never changes any arm's optimum). */
    val shareCuts: Boolean = true,
    /** LBD bound of the cross-arm glue-clause exchange filter. */
    val clauseShareMaxLbd: Int = 6,
    /** Length bound of the cross-arm glue-clause exchange filter; see [clauseShareMaxLbd]. */
    val clauseShareMaxLen: Int = 12,
    /**
     * Work a sequential-portfolio arm's first segment may spend, in the node-equivalents every arm is
     * charged in.
     *
     * A segment bounded by time pauses somewhere different on every run, so no counter a solve reports
     * is comparable between two invocations of the same model — the search itself diverges. Bounding it
     * by work makes a segment a function of the model and the seed alone. The whole-solve deadline still
     * applies, so this cannot overrun it.
     */
    val sliceWork: Long = DEFAULT_SLICE_WORK,
    /** Share of the arm bandit's evidence kept across the first incumbent; see [Portfolio.DEFAULT_PHASE_RETENTION]. */
    val phaseRetention: Double = Portfolio.DEFAULT_PHASE_RETENTION,
    /** Optional solve-spanning decision-node allowance, applied here rather than by the caller so that
     *  every arm that runs a backtrack engine spends the one counter — including the ones that build
     *  their own [BacktrackParams] instead of drawing a [BacktrackRecipe] from a pool. Editing the pools
     *  from outside reaches only the latter, which both leaves the hybrid-ALNS arm's repair unbounded and
     *  substitutes a pool, so the capped run measures a different arm set than the uncapped one. */
    val nodeBudget: NodeBudget? = null,
) {
    init {
        require(cores >= 1) { "cores must be ≥ 1" }
        require(arms >= 1) { "arms must be ≥ 1" }
        require(clauseShareMaxLbd >= 0 && clauseShareMaxLen >= 0) { "clause-share filter bounds must be ≥ 0" }
        require(phaseRetention in 0.0..1.0) { "phaseRetention must be in [0, 1]" }
    }

    /** Factories for the two execution shapes a scenario can take. */
    companion object {
        /** Default arm-pool size when a caller doesn't specify one — larger than a single core so the
         *  sequential free track bandit-schedules a real pool, not one arm. */
        const val DEFAULT_ARMS = 6

        /** Default work in an arm's first segment; later segments grow. Mirrors
         *  [Portfolio.baseSliceWork]. */
        const val DEFAULT_SLICE_WORK = 5_000L

        /** A parallel portfolio over [cores] cores; [arms] defaults to one arm per core. */
        fun parallel(cores: Int, kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L, arms: Int = cores) =
            PortfolioScenario(cores = cores, arms = arms, kind = kind, engine = engine, seed = seed)

        /** A single-core, bandit-scheduled portfolio (the competition free/fixed track) over an
         *  [arms]-arm pool. */
        fun sequential(kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L, arms: Int = DEFAULT_ARMS) =
            PortfolioScenario(cores = 1, arms = arms, kind = kind, engine = engine, seed = seed)
    }
}

/**
 * A composed-but-not-yet-materialised portfolio arm — one entry of either catalog
 * ([LocalSearchWorkerConfig] / [BacktrackWorkerConfig]). Each arm knows how to build its own
 * [PortfolioWorker] over a problem, so [PortfolioBuilder] is a thin `map` and there is no
 * engine-specific switch: the two engines stay symmetric ("arms in one spot").
 */
internal sealed interface WorkerConfig {
    /** Telemetry id (the catalog label, before the engine prefix is added in [materialize]). */
    val label: String

    /**
     * Build the runnable worker. [index] is the arm's position in the pool (offsets the seed). [armId]
     * is the composed-arm identity (replicas of one arm share it while their [index] differs); it is
     * pure attribution metadata, never scheduling or seed input. [objective] is the canonical
     * [LinearObjective] every optimising worker minimises; [lsObjective] is the optional LS gradient view of the same
     * objective (backtrack ignores it). [lsLambda]/[definitionalSweep] are LS-only (backtrack
     * ignores them). [onEvent] is the shared [SearchEvent] sink, tagged here with the worker's
     * label. [pools], when non-null, wires the cross-arm clause and cut exchanges (backtrack arms
     * only; LS ignores it).
     */
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

internal class PortfolioArmPlan(val arms: List<WorkerConfig>, val firstSolutionCount: Int)

/**
 * The single generic decision algorithm: given a [PortfolioScenario], pick and order the arms.
 * This is the whole portfolio policy in one place — and the surface issue #9 tunes. Everything
 * scenario-dependent (which arms, how many, in what order, how a mixed pool splits) is decided
 * here; [PortfolioBuilder] only materialises the result, identically for every scenario.
 */
internal object PortfolioComposition {

    /** Fraction of a MIXED pool given to local-search arms (the rest go to backtrack). A #9 knob.
     *  COP leans LS (it streams good incumbents fast while backtrack tightens the bound); CSP leans
     *  backtrack (only complete search proves UNSAT / reliably reaches a first feasible). */
    fun lsShare(kind: Kind): Double = when (kind) {
        // COP leans LS — it streams good incumbents while backtrack tightens the bound (≈ 4 LS : 2 BT).
        Kind.COP -> 2.0 / 3.0

        // CSP leans backtrack — only complete search proves UNSAT / reliably reaches a first feasible.
        Kind.CSP -> 1.0 / 3.0
    }

    /**
     * The ordered arm list for [scenario]: [PortfolioScenario.arms] arms, and a hybrid-ALNS arm beside them in a
     * scheduled mixed optimization pool. The curated pools keep only
     * the arms the model behind [facts] offers the needs of. A pool the caller chose outright — an injected one, or
     * a single-engine mix — is built as asked, so a model it cannot run is declined rather than replaced.
     */
    fun compose(
        scenario: PortfolioScenario,
        facts: ProblemFacts = ProblemFacts.assumed(scenario.kind),
    ): List<WorkerConfig> {
        val arms = composeArms(scenario, facts)
        val check = scenario.toleranceCheck ?: return arms
        return arms.map { if (it is BacktrackWorkerConfig) it.withToleranceCheck(check) else it }
    }

    internal fun plan(scenario: PortfolioScenario, facts: ProblemFacts): PortfolioArmPlan {
        val composed = compose(scenario, facts)
        if (scenario.cores != 1 || scenario.engine != EngineMix.MIXED || scenario.kind != Kind.COP ||
            scenario.arms <= PortfolioScenario.DEFAULT_ARMS || scenario.lsPool != null || scenario.btPool != null
        ) {
            return PortfolioArmPlan(composed, composed.size)
        }
        // Keep the small pool's positions: materialization derives each arm's seed from its position.
        val small = compose(scenario.copy(arms = PortfolioScenario.DEFAULT_ARMS), facts)
        val remaining = composed.toMutableList()
        val first = small.mapNotNull { arm ->
            val index = remaining.indexOfFirst { it::class == arm::class && it.label == arm.label }
            if (index >= 0) remaining.removeAt(index) else null
        }
        return PortfolioArmPlan(first + remaining, first.size)
    }

    private fun composeArms(scenario: PortfolioScenario, facts: ProblemFacts): List<WorkerConfig> {
        val count = scenario.arms
        return when (scenario.engine) {
            EngineMix.LOCAL_SEARCH -> lsArms(scenario.kind, count, scenario.lsPool, scenario.nodeBudget, facts)
            EngineMix.BACKTRACK -> btArms(scenario, count, facts)
            EngineMix.MIXED -> mixedArms(scenario, facts)
            EngineMix.ALNS -> alnsArms(count, scenario.nodeBudget)
        }
    }

    /** The [count] hybrid-ALNS arms, cycling the curated regimes ([AlnsProfile.Curated]) — the ALNS analog
     *  of [lsArms]. COP-oriented: with no objective each arm's [com.eignex.klause.meta.alns.Alns] degrades
     *  to its inner local search via `solve`, so the engine is still well-defined on a CSP. */
    private fun alnsArms(count: Int, nodeBudget: NodeBudget?): List<WorkerConfig> =
        AlnsWorkerConfig.diverse(count, nodeBudget)

    /** The [count] LS arms — the curated pool ([pool] == null), else the CLI's resolved pool, each
     *  slot a fresh recipe (wrapping past the pool size) — every one spending [nodeBudget]. */
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

    /** The [count] backtrack arms for [scenario]. [PortfolioScenario.btPool] (when set) overrides the pool
     *  with injected templates. Otherwise the curated pool the model behind [facts] offers the needs of, with
     *  the model's [PortfolioScenario.annotationArm] taking the last slot when present and there are ≥ 2 slots
     *  (so the `satOptimized` guard keeps slot 0). Every resulting arm spends [PortfolioScenario.nodeBudget],
     *  which is applied to the composed pool so that capping a run does not also change which pool it composes. */
    private fun btArms(scenario: PortfolioScenario, count: Int, facts: ProblemFacts): List<WorkerConfig> {
        val lpCeiling = scenario.lpCeiling
        val btPool = scenario.btPool
        val annotationArm = scenario.annotationArm
        val nodeBudget = scenario.nodeBudget
        val zeroObjectivePricing = scenario.zeroObjectivePricing
        val edit = scenario.btEdit
        if (btPool != null) {
            // The `--lp` ceiling bounds the pool, and an injected pool is still the pool: capping only the
            // curated one leaves `--lp` silently ignored whenever the caller names its arms.
            return List(count) {
                BacktrackWorkerConfig(
                    btPool[it % btPool.size]().editing(edit).capLp(lpCeiling).spending(nodeBudget),
                    zeroObjectivePricing,
                )
            }
        }
        val base = BacktrackWorkerConfig.diverse(
            scenario.kind,
            count,
            lpCeiling,
            nodeBudget,
            zeroObjectivePricing,
            facts,
            edit,
        )
        if (annotationArm == null || count < 2) return base
        val annotation = BacktrackWorkerConfig.ofParams("annotation", annotationArm.copy(nodeBudget = nodeBudget))
        return base.dropLast(1) + BacktrackWorkerConfig(annotation, zeroObjectivePricing)
    }

    private fun mixedArms(scenario: PortfolioScenario, facts: ProblemFacts): List<WorkerConfig> {
        // At least one of each engine once count ≥ 2; below that the single slot goes to LS (the
        // fast first-incumbent engine).
        val count = scenario.arms
        val lsCount = (count * lsShare(scenario.kind)).roundToInt().coerceIn(if (count >= 2) 1 else count, count)
        val btCount = count - lsCount
        // Hybrid ALNS with CP repair joins a scheduled optimization pool whose model offers what it needs, on top
        // of the arms: the policy shares time by family, so an extra arm takes no time from the others. It works
        // around an incumbent, so a satisfaction model has nothing for it, and a pool with a core per arm keeps
        // its cores.
        val alns = scenario.kind == Kind.COP && scenario.cores < count && facts.offersAll(AlnsWorkerConfig.NEEDS)
        val arms = ArrayList<WorkerConfig>(count + 1)
        val local = if (lsCount > 0) {
            lsArms(scenario.kind, lsCount, scenario.lsPool, scenario.nodeBudget, facts)
        } else {
            emptyList()
        }
        val backtrack = if (btCount > 0) btArms(scenario, btCount, facts) else emptyList()
        if (scenario.kind == Kind.COP) {
            // Sequential portfolios warm every arm in list order. A complete arm must receive its first
            // slice before the local-search incumbents, which cannot prove an optimum and otherwise delay
            // a trivial optimization by the entire local-search warmup.
            arms += backtrack
            arms += local
        } else {
            arms += local
            arms += backtrack
        }
        if (alns) arms += AlnsWorkerConfig(nodeBudget = scenario.nodeBudget)
        return arms
    }
}
