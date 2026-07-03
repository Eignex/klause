package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.LsRecipe
import com.eignex.klause.solver.Problem
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
 * [arms] arms on real threads. [arms] is independent of [cores] (≥ it): the single-core sequential
 * track still draws on a multi-arm pool, and a parallel track may carry more arms than cores.
 */
data class PortfolioScenario(
    /** Compute width. `1` selects the single-core sequential executor; `> 1` the parallel one. */
    val cores: Int,
    /** Pool size — how many arms [PortfolioComposition.compose] produces. Independent of [cores], but
     *  never fewer (a parallel track needs at least one arm per core). */
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
     *  `null` uses the curated `LsCatalog` pool unchanged; a non-null pool is the CLI's resolved
     *  recipes (a named base, or the curated pool with axis edits applied). */
    val lsPool: List<() -> LsRecipe>? = null,
    /** Whether the backtrack arms share globally-valid LP cuts through a [SharedCutPool] (#809), the
     *  cut analogue of the always-on learned-clause pool. On by default; sound either way (only global
     *  cuts cross arms, so it never changes any arm's optimum). */
    val shareCuts: Boolean = true,
) {
    init {
        require(cores >= 1) { "cores must be ≥ 1" }
        require(arms >= cores) { "arms must be ≥ cores (got arms=$arms, cores=$cores)" }
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
     * for backtrack, numbers the label). [objective] is the canonical [LinearObjective] every
     * optimising worker minimises; [lsObjective] is the optional LS gradient view of the same
     * objective (backtrack ignores it). [lsLambda]/[definitionalSweep] are LS-only (backtrack
     * ignores them). [onEvent] is the shared [SearchEvent] sink, tagged here with the worker's
     * label. [pools], when non-null, wires the cross-arm clause and cut exchanges (backtrack arms
     * only; LS ignores it).
     */
    fun materialize(
        problem: Problem,
        index: Int,
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
            EngineMix.LOCAL_SEARCH -> lsArms(count, scenario.lsPool)
            EngineMix.BACKTRACK -> btArms(scenario.kind, count, scenario.lpCeiling)
            EngineMix.MIXED -> mixedArms(scenario.kind, count, scenario.lpCeiling, scenario.lsPool)
        }
    }

    /** The [count] LS arms — the curated pool ([pool] == null), else the CLI's resolved pool, each
     *  slot a fresh recipe (wrapping past the pool size). */
    private fun lsArms(count: Int, pool: List<() -> LsRecipe>?): List<WorkerConfig> = if (pool == null) {
        LocalSearchWorkerConfig.diverse(count)
    } else {
        List(count) { LocalSearchWorkerConfig(pool[it % pool.size]()) }
    }

    private fun btArms(kind: Kind, count: Int, lpCeiling: LpConfig): List<WorkerConfig> =
        BacktrackWorkerConfig.diverse(kind, count, lpCeiling)

    private fun mixedArms(
        kind: Kind,
        count: Int,
        lpCeiling: LpConfig,
        pool: List<() -> LsRecipe>?,
    ): List<WorkerConfig> {
        // At least one of each engine once count ≥ 2; below that the single slot goes to LS (the
        // fast first-incumbent engine).
        val lsCount = (count * lsShare(kind)).roundToInt().coerceIn(if (count >= 2) 1 else count, count)
        val btCount = count - lsCount
        val arms = ArrayList<WorkerConfig>(count)
        arms += lsArms(lsCount, pool)
        if (btCount > 0) arms += btArms(kind, btCount, lpCeiling)
        return arms
    }
}
