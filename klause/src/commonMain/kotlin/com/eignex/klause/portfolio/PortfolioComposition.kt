package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.IncrementalObjective
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
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
 * A point in the portfolio configuration space, described by the three orthogonal axes a portfolio
 * must adapt to: **threads** (compute width), **kind** (COP vs CSP), and **engine** (LS / backtrack
 * / mixed). [PortfolioComposition.compose] turns a scenario into an ordered arm list, and
 * [PortfolioBuilder.build] materialises that list into runnable [PortfolioWorker]s — so every
 * scenario flows through one construction path.
 *
 * [threads] is the available concurrency. `threads > 1` ⇒ a parallel [Portfolio] of that many
 * workers (one arm per core, repeating the strong arms on fresh seeds past the pool size).
 * `threads == 1` ⇒ a single core: a [SequentialPortfolio] bandit-schedules a small pool
 * ([PortfolioComposition.SEQUENTIAL_POOL_SIZE]) of arms one slice at a time — pool size is
 * decoupled from the one core.
 */
data class PortfolioScenario(
    /** Available concurrency / pool width; `1` selects the single-core sequential executor (see
     *  the class KDoc). */
    val threads: Int,
    /** Whether the problem optimizes (COP) or only satisfies (CSP). */
    val kind: Kind,
    /** Which engine family supplies the arms (LS / backtrack / mixed). */
    val engine: EngineMix,
    /** Base RNG seed; worker `i` offsets it so the pool explores distinct trajectories. */
    val seed: Long = 0L,
    /** Objective-shaping λ for the LS workers' optimize phase (mirrors the CLI's CBLS λ=1.0). */
    val lsLambda: Double = 1.0,
) {
    init {
        require(threads >= 1) { "threads must be ≥ 1" }
    }

    /** Factories for the two execution shapes a scenario can take. */
    companion object {
        /** A parallel portfolio of [threads] workers. */
        fun parallel(threads: Int, kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L) =
            PortfolioScenario(threads, kind, engine, seed)

        /** A single-core, bandit-scheduled portfolio (the competition free/fixed track). */
        fun sequential(kind: Kind, engine: EngineMix = EngineMix.MIXED, seed: Long = 0L) =
            PortfolioScenario(threads = 1, kind = kind, engine = engine, seed = seed)
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
     * label.
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
    ): PortfolioWorker
}

/**
 * The single generic decision algorithm: given a [PortfolioScenario], pick and order the arms.
 * This is the whole portfolio policy in one place — and the surface issue #9 tunes. Everything
 * scenario-dependent (which arms, how many, in what order, how a mixed pool splits) is decided
 * here; [PortfolioBuilder] only materialises the result, identically for every scenario.
 */
internal object PortfolioComposition {

    /** Arm count the single-core [SequentialPortfolio] bandit schedules over (decoupled from the
     *  one core — the bandit time-slices, it does not run them concurrently). */
    const val SEQUENTIAL_POOL_SIZE = 6

    /** Fraction of a MIXED pool given to local-search arms (the rest go to backtrack). A #9 knob.
     *  COP leans LS (it streams good incumbents fast while backtrack tightens the bound); CSP leans
     *  backtrack (only complete search proves UNSAT / reliably reaches a first feasible). */
    fun lsShare(kind: Kind): Double = when (kind) {
        // COP leans LS — it streams good incumbents while backtrack tightens the bound (≈ 4 LS : 2 BT).
        Kind.COP -> 2.0 / 3.0

        // CSP leans backtrack — only complete search proves UNSAT / reliably reaches a first feasible.
        Kind.CSP -> 1.0 / 3.0
    }

    /** The ordered arm list for [scenario]. */
    fun compose(scenario: PortfolioScenario): List<WorkerConfig> {
        val count = if (scenario.threads == 1) SEQUENTIAL_POOL_SIZE else scenario.threads
        return when (scenario.engine) {
            EngineMix.LOCAL_SEARCH -> lsArms(count)
            EngineMix.BACKTRACK -> btArms(scenario.kind, count)
            EngineMix.MIXED -> mixedArms(scenario.kind, count)
        }
    }

    private fun lsArms(count: Int): List<WorkerConfig> = LocalSearchWorkerConfig.diverse(count)

    private fun btArms(kind: Kind, count: Int): List<WorkerConfig> = BacktrackWorkerConfig.diverse(kind, count)

    private fun mixedArms(kind: Kind, count: Int): List<WorkerConfig> {
        // At least one of each engine once count ≥ 2; below that the single slot goes to LS (the
        // fast first-incumbent engine).
        val lsCount = (count * lsShare(kind)).roundToInt().coerceIn(if (count >= 2) 1 else count, count)
        val btCount = count - lsCount
        val arms = ArrayList<WorkerConfig>(count)
        arms += lsArms(lsCount)
        if (btCount > 0) arms += btArms(kind, btCount)
        return arms
    }
}
