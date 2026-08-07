package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.solver.BakedProblem
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

    /** Hybrid ALNS arms only — a large-neighbourhood destroy/repair loop with CP repair (#644). */
    ALNS,
}

/**
 * A point in the portfolio configuration space. Its axes are **cores** (compute width) and **arms**
 * (pool size) — kept separate so they don't conflate (#406) — plus **kind** (COP vs CSP) and
 * **engine** (LS / backtrack / mixed). [PortfolioComposition.compose] turns a scenario into an ordered
 * arm list of size [arms], and [PortfolioBuilder.build] materialises it into runnable
 * [PortfolioWorker]s — so every scenario flows through one construction path.
 *
 * [cores] selects the executor: `cores == 1` ⇒ a single-core [SequentialPortfolio] that bandit-
 * schedules the [arms] arms one time-slice at a time; `cores > 1` ⇒ a parallel `Portfolio` running the
 * composed arms on real threads. [arms] and [cores] are fully independent: the single-core sequential
 * track still draws on a multi-arm pool, a parallel track may carry more arms than cores, and — when
 * `arms < cores` — [PortfolioBuilder.build] replicates the composed arms across the extra lanes with
 * distinct seeds, so a parallel run can be wider than its pool of distinct configs.
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
    /** The LP ceiling for the backtrack arms (#429, the `--lp` parameter): each LP arm is capped under
     *  this — its emphasis lowered to the ceiling's and the ceiling's per-technique overrides applied.
     *  `LpConfig.AGGRESSIVE` (default, no overrides) leaves the arms uncapped — the pool spreads the
     *  LP-intensity itself; an `OFF` emphasis disables LP, and overrides force individual techniques. */
    val lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
    /** Optional override of the local-search arm pool — per-arm factories (a fresh recipe per slot).
     *  `null` uses the curated `LocalSearchCatalog` pool unchanged; a non-null pool is the CLI's resolved
     *  recipes (a named base, or the curated pool with axis edits applied). */
    val lsPool: List<() -> LocalSearchRecipe>? = null,
    /** Optional override of the backtrack arm pool — per-arm [BacktrackRecipe] factories (a fresh
     *  recipe per slot, wrapping past the pool size), the exact backtrack analogue of [lsPool]. `null`
     *  uses the curated [BacktrackWorkerConfig] pool. */
    val btPool: List<() -> BacktrackRecipe>? = null,
    /** Optional model search-annotation arm (#512): the [BacktrackParams] compiled from the model's
     *  `int_search(...)` annotations. When present (and the pool carries ≥ 2 backtrack arms), it takes
     *  the last backtrack slot so the free CP portfolio also follows the model's own search order,
     *  while the #117 `satOptimized` guard keeps slot 0. Ignored when [btPool] overrides the pool. */
    val annotationArm: BacktrackParams? = null,
    /** Whether the backtrack arms share globally-valid LP cuts through a [SharedCutPool] (#809), the
     *  cut analogue of the always-on learned-clause pool. On by default; sound either way (only global
     *  cuts cross arms, so it never changes any arm's optimum). */
    val shareCuts: Boolean = true,
    /** LBD bound of the cross-arm glue-clause exchange filter. 6/12 measured best on the 4-core
     *  backtrack pool at 10s: it beats the historical 4/8 by Borda 189–138 over dimacs-classic +
     *  pb-comp combined (28–9 strict wins, +2 decided) and beats 9/20 by 186–141 — moderate widening
     *  shares more useful clauses, past that the import volume costs more than it prunes. Both
     *  halves stay exposed (`--param clause-share-lbd` / `clause-share-len`) for per-run tuning. */
    val clauseShareMaxLbd: Int = 6,
    /** Length bound of the cross-arm glue-clause exchange filter; see [clauseShareMaxLbd]. */
    val clauseShareMaxLen: Int = 12,
) {
    init {
        require(cores >= 1) { "cores must be ≥ 1" }
        require(arms >= 1) { "arms must be ≥ 1" }
        require(clauseShareMaxLbd >= 0 && clauseShareMaxLen >= 0) { "clause-share filter bounds must be ≥ 0" }
    }

    /** Factories for the two execution shapes a scenario can take. */
    companion object {
        /** Default arm-pool size when a caller doesn't specify one — larger than a single core so the
         *  sequential free track bandit-schedules a real pool, not one arm. */
        const val DEFAULT_ARMS = 6

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
     * Build the runnable worker. [index] is the arm's position in the pool (offsets the seed and,
     * for backtrack, numbers the label). [armId] is the composed-arm identity (replicas of one arm
     * share it while their [index] differs); it is pure attribution metadata, never scheduling or
     * seed input. [objective] is the canonical [LinearObjective] every
     * optimising worker minimises; [lsObjective] is the optional LS gradient view of the same
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

    /** The ordered arm list for [scenario] — exactly [PortfolioScenario.arms] arms. */
    fun compose(scenario: PortfolioScenario): List<WorkerConfig> {
        val count = scenario.arms
        return when (scenario.engine) {
            EngineMix.LOCAL_SEARCH -> lsArms(scenario.kind, count, scenario.lsPool)

            EngineMix.BACKTRACK -> btArms(
                scenario.kind,
                count,
                scenario.lpCeiling,
                scenario.btPool,
                scenario.annotationArm,
            )

            EngineMix.MIXED -> mixedArms(scenario)

            EngineMix.ALNS -> alnsArms(count)
        }
    }

    /** The [count] hybrid-ALNS arms, cycling the curated regimes ([AlnsProfile.Curated]) — the ALNS analog
     *  of [lsArms]. COP-oriented: with no objective each arm's [com.eignex.klause.meta.alns.Alns] degrades
     *  to its inner local search via `solve`, so the engine is still well-defined on a CSP. */
    private fun alnsArms(count: Int): List<WorkerConfig> = AlnsWorkerConfig.diverse(count)

    /** The [count] LS arms — the curated pool ([pool] == null), else the CLI's resolved pool, each
     *  slot a fresh recipe (wrapping past the pool size). */
    private fun lsArms(kind: Kind, count: Int, pool: List<() -> LocalSearchRecipe>?): List<WorkerConfig> =
        if (pool == null) {
            LocalSearchWorkerConfig.diverse(kind, count)
        } else {
            List(count) { LocalSearchWorkerConfig(pool[it % pool.size]()) }
        }

    /** The [count] backtrack arms. [btPool] (when set) overrides the pool with injected templates.
     *  Otherwise the curated pool, with the model's [annotationArm] (#512) taking the last slot when
     *  present and there are ≥ 2 slots (so the #117 `satOptimized` guard keeps slot 0). */
    private fun btArms(
        kind: Kind,
        count: Int,
        lpCeiling: LpConfig,
        btPool: List<() -> BacktrackRecipe>?,
        annotationArm: BacktrackParams?,
    ): List<WorkerConfig> {
        if (btPool != null) {
            return List(count) { BacktrackWorkerConfig(btPool[it % btPool.size]()) }
        }
        val base = BacktrackWorkerConfig.diverse(kind, count, lpCeiling)
        if (annotationArm == null || count < 2) return base
        return base.dropLast(1) + BacktrackWorkerConfig(BacktrackWorkerConfig.ofParams("annotation", annotationArm))
    }

    private fun mixedArms(scenario: PortfolioScenario): List<WorkerConfig> {
        // At least one of each engine once count ≥ 2; below that the single slot goes to LS (the
        // fast first-incumbent engine).
        val count = scenario.arms
        val lsCount = (count * lsShare(scenario.kind)).roundToInt().coerceIn(if (count >= 2) 1 else count, count)
        val btCount = count - lsCount
        val arms = ArrayList<WorkerConfig>(count)
        arms += lsArms(scenario.kind, lsCount, scenario.lsPool)
        if (btCount > 0) {
            arms += btArms(scenario.kind, btCount, scenario.lpCeiling, scenario.btPool, scenario.annotationArm)
        }
        // Hybrid ALNS with CP repair (#644): a mixed LS+backtrack engine, added last (lowest priority,
        // pending its credit pass). COP only — it optimises an incumbent, so a CSP has nothing for it.
        if (scenario.kind == Kind.COP) arms += AlnsWorkerConfig()
        return arms
    }
}
