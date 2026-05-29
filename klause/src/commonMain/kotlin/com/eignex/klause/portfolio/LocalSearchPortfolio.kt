@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LocalSearchSession
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LubyRestart
import com.eignex.klause.solver.localsearch.PerturbationKind
import com.eignex.klause.solver.localsearch.RestartPolicy
import com.eignex.klause.solver.localsearch.strategy.AdaptiveProbSat
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.localsearch.strategy.Vnd
import com.eignex.klause.solver.localsearch.strategy.WalkSat
import kotlin.concurrent.atomics.AtomicReference

/**
 * Build a diverse pool of [LocalSearchSession] workers for a multi-core LS [Portfolio].
 * Provides three coordinated capabilities:
 *
 *  - **Per-worker strategy selection** ([config]): each worker gets a distinct
 *    `(Strategy, RestartPolicy)` pair from the supplied [LocalSearchWorkerConfig] list,
 *    so the portfolio explores algorithmically-orthogonal trajectories in parallel.
 *  - **Best-feasible sharing** ([sharedBest]): workers publish their incumbent samples
 *    into a shared atomic reference exposed back through the [LocalSearchSession]'s
 *    warm-start hook; on restart, a worker that hasn't found anything yet anchors to
 *    the global best instead of a fresh random assignment.
 *  - **Shared kumulant stats** ([restartBandit]): an optional univariate bandit over the
 *    worker configs that gets rewarded when a worker improves the shared best, so future
 *    restart cycles can switch a stalled worker to a more-promising config.
 *
 * Use:
 * ```
 * val configs = LocalSearchWorkerConfig.diverse(8)
 * val workers = LocalSearchPortfolio.workers(problem, configs)
 * val portfolio = Portfolio(workers)
 * ```
 */
data class LocalSearchWorkerConfig(
    val label: String,
    val strategy: Strategy,
    val restartPolicy: RestartPolicy,
) {
    companion object {
        /** A diverse default palette of [count] worker configs, cycling through
         *  adaptive-probSAT / WalkSAT / DDFW / SA strategies and FixedCadence / Luby /
         *  ILS / AdaptivePerturbation restart policies. Each (strategy, restart) pair is
         *  chosen for orthogonal exploration behaviour — the portfolio benefits from
         *  diverse trajectories on the same problem. */
        fun diverse(count: Int): List<LocalSearchWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val palette = listOf(
                LocalSearchWorkerConfig(
                    "adaptive-probsat/fixed",
                    AdaptiveProbSat(tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)),
                    FixedCadenceRestart(),
                ),
                LocalSearchWorkerConfig(
                    "walksat/luby",
                    WalkSat(noise = 0.5, tabu = TabuFilter(tenure = 5)),
                    LubyRestart(unit = 200),
                ),
                LocalSearchWorkerConfig(
                    "probsat/ils-basin",
                    ProbSat(cb = 2.5, tabu = TabuFilter(tenure = 8)),
                    IteratedLocalSearchRestart(
                        populationSize = 3,
                        crossoverRate = 0.25,
                        perturbationKind = PerturbationKind.BasinHopping,
                        acceptance = AcceptanceCriterion.Improving,
                    ),
                ),
                LocalSearchWorkerConfig(
                    "sa/fixed",
                    SimulatedAnnealing(),
                    FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
                ),
                LocalSearchWorkerConfig(
                    "vnd/ils-linkage",
                    Vnd(maxNeighborhood = 3, skewAlpha = 0.2),
                    IteratedLocalSearchRestart(
                        populationSize = 5,
                        crossoverRate = 0.4,
                        linkageAware = true,
                    ),
                ),
            )
            return List(count) { palette[it % palette.size] }
        }
    }
}

/**
 * Static-side factory for assembling multi-core LS portfolios with shared state.
 *
 * The factory holds the shared atomic incumbent and exposes a *consumer* hook so
 * [Portfolio.minimize] (or a custom outer driver) can plug them into each worker's
 * params or restart-policy. Workers are constructed as plain [LocalSearchSession]
 * instances over private [LocalSearchSolver]s — they share the [Problem] but not
 * mutable state, so cross-worker contention is bounded by the atomic publishes.
 */
class LocalSearchPortfolio(
    val problem: Problem,
    val configs: List<LocalSearchWorkerConfig>,
) {
    init {
        require(configs.isNotEmpty()) { "Need at least one worker config" }
    }

    /** Shared atomic incumbent updated by workers when they find a better feasible sample.
     *  Consumers (e.g. the restart policy) read this to seed warm-restarts on stalled
     *  workers. Updated under a CAS so concurrent writes don't race past each other. */
    val sharedBest: AtomicReference<Sample?> = AtomicReference(null)

    /** Per-config [LocalSearchSession] for direct portfolio composition. */
    val workers: List<LocalSearchSession> = configs.map { cfg ->
        LocalSearchSolver(problem, strategy = cfg.strategy, restartPolicy = cfg.restartPolicy).session()
    }

    /** Try to update [sharedBest] with [sample]; returns true if accepted as the new
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
     * or any other [com.eignex.kumulant.bandit.UnivariateBandit]; reading and updating
     * the bandit are the caller's responsibility — this slot is purely the shared
     * handle. Defaults to null (no cross-worker learning).
     */
    var restartBandit: com.eignex.kumulant.bandit.UnivariateBandit? = null

    fun close() {
        workers.forEach { runCatching { it.close() } }
    }
}
