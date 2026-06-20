@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSession
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.LsCatalog
import com.eignex.klause.solver.localsearch.strategy.LsRecipe
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.kumulant.bandit.UnivariateBandit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A portfolio arm wrapping a curated [LsRecipe] for execution: it materialises the recipe into a
 * runnable [LocalSearchSession] worker. The recipe owns the four axes (restart included, in its
 * schedule); this adapter owns only the run-time wiring (λ-shaping, warm-start, event sink).
 *
 * Use:
 * ```
 * val configs = LocalSearchWorkerConfig.diverse(8)
 * val workers = LocalSearchPortfolio.workers(problem, configs)
 * val portfolio = Portfolio(workers)
 * ```
 */
internal class LocalSearchWorkerConfig(val recipe: LsRecipe) : WorkerConfig {

    override val label: String get() = recipe.label

    /** Build an LS worker: its [LocalSearchSolver] session (with the per-move invariant network when
     *  a [definitionalSweep] is present and the recipe enables it) + λ-shaped params, exposing the
     *  warm-start seam so a [SequentialPortfolio] can resume a segment from the shared incumbent. The
     *  restart cadence rides on the recipe's `strategy.schedule.restart`. Label is `ls/<label>`. */
    override fun materialize(
        problem: Problem,
        index: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        clausePool: SharedClausePool?, // ignored: local search neither learns nor consumes clauses
    ): PortfolioWorker {
        val session = LocalSearchSolver(
            problem,
            strategy = recipe.strategy,
            optimizeStrategy = recipe.optimizeStrategy,
            definitionalSweep = definitionalSweep,
            perMoveInvariants = definitionalSweep != null && recipe.perMoveInvariants,
            seedImplicitOnRestart = recipe.seedImplicitOnRestart,
        ).session()
        val workerLabel = "ls/$label"
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            // The per-move gradient view of the objective, when the model provides one.
            lsObjective = lsObjective,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
            // Keep a single over-populated constraint kind from steering the initial descent; a
            // no-op for the pool's weight-blind arms.
            normalizeWeightsByClass = true,
        )
        return PortfolioWorker.of(
            workerLabel,
            session,
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
        )
    }

    companion object {
        /** A fresh instance of the pool config named [label] (the string boundary). */
        fun byLabel(label: String): LocalSearchWorkerConfig = LocalSearchWorkerConfig(LsCatalog.byLabel(label))

        /** One fresh instance of every pool config, in credit order. */
        fun pool(): List<LocalSearchWorkerConfig> = LsCatalog.auto().map { LocalSearchWorkerConfig(it) }

        /** The top-[count] prefix of the credit-ordered pool (wrapping past the pool size) — `-p <n>`
         *  maps straight onto this. Every slot is a fresh instance even when arms repeat. */
        fun diverse(count: Int): List<LocalSearchWorkerConfig> =
            LsCatalog.diverse(count).map { LocalSearchWorkerConfig(it) }
    }
}

/**
 * Static-side factory for assembling multi-core LS portfolios with shared state.
 *
 * The factory holds the shared atomic incumbent and exposes a *consumer* hook so
 * `Portfolio.minimize` (or a custom outer driver) can plug them into each worker's
 * params or restart-policy. Workers are constructed as plain [LocalSearchSession]
 * instances over private [LocalSearchSolver]s — they share the [Problem] but not
 * mutable state, so cross-worker contention is bounded by the atomic publishes.
 */
internal class LocalSearchPortfolio(val problem: Problem, val configs: List<LocalSearchWorkerConfig>) {
    init {
        require(configs.isNotEmpty()) { "Need at least one worker config" }
    }

    /** Shared atomic incumbent updated by workers when they find a better feasible sample.
     *  Consumers (e.g. the restart policy) read this to seed warm-restarts on stalled
     *  workers. Updated under a CAS so concurrent writes don't race past each other. */
    val sharedBest: AtomicReference<Sample?> = AtomicReference(null)

    /** Per-config [LocalSearchSession] for direct portfolio composition. The restart cadence rides on
     *  each recipe's `strategy.schedule.restart`. */
    val workers: List<LocalSearchSession> = configs.map { cfg ->
        LocalSearchSolver(
            problem,
            strategy = cfg.recipe.strategy,
            optimizeStrategy = cfg.recipe.optimizeStrategy,
        ).session()
    }

    /** Try to update `sharedBest` with [sample]; returns true if accepted as the new
     *  global best (lower objective via [objectiveOf]). Workers should call this when
     *  they find a feasible local optimum (cost == 0 typically). */
    inline fun publishIfBetter(sample: Sample, objectiveOf: (Sample) -> Double): Boolean {
        val newObj = objectiveOf(sample)
        while (true) {
            val cur = sharedBest.load()
            if (cur != null && objectiveOf(cur) <= newObj) return false
            if (sharedBest.compareAndSet(cur, sample)) return true
        }
    }

    /**
     * Optional shared kumulant univariate bandit over the worker configs. Workers
     * arm-pull = pick their config index; the portfolio rewards arms whose worker
     * improved the global incumbent on the last cycle.
     *
     * Set up by the caller using `kumulant.bandit.RouletteWheelBandit(configs.size)`
     * or any other [UnivariateBandit]; reading and updating
     * the bandit are the caller's responsibility — this slot is purely the shared
     * handle. Defaults to null (no cross-worker learning).
     */
    var restartBandit: UnivariateBandit? = null

    fun close() {
        workers.forEach { runCatching { it.close() } }
    }
}
