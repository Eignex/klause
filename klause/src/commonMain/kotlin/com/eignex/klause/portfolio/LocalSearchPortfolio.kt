@file:OptIn(ExperimentalAtomicApi::class)

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
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.localsearch.strategy.WalkSat
import com.eignex.kumulant.bandit.UnivariateBandit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
internal data class LocalSearchWorkerConfig(
    val label: String,
    val strategy: Strategy,
    val restartPolicy: RestartPolicy,
    /** Minimize-phase strategy. `null` reuses [strategy]. Set to a [Cbls] instance to engage
     *  the unified minimize path (CBLS drives both feasibility fight and objective descent);
     *  required for a CBLS worker to actually use CBLS for the objective rather than the
     *  built-in greedy descent. */
    val optimizeStrategy: Strategy? = null,
) {
    companion object {
        /** A diverse default palette of [count] worker configs. CBLS leads (the strongest
         *  general strategy — constraint-violation gradient with int-aware moves and weight
         *  learning, best on the CP shape MiniZinc produces), followed by orthogonal members
         *  for coverage: adaptive probSAT for clausal SAT, WalkSAT+configuration-checking for
         *  structured SAT, and simulated annealing for rugged escape. The first [count] entries
         *  are taken (wrapping when `count` exceeds the palette), so small portfolios get the
         *  highest-value workers first. */
        fun diverse(count: Int): List<LocalSearchWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val cblsTabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
            val palette = listOf(
                // The constraint-based workhorse: strongest on CP-shaped satisfaction and
                // optimization. optimizeStrategy set so minimize runs CBLS's unified path.
                LocalSearchWorkerConfig(
                    "cbls/fixed",
                    strategy = Cbls(tabu = cblsTabu),
                    restartPolicy = FixedCadenceRestart(),
                    optimizeStrategy = Cbls(tabu = cblsTabu),
                ),
                // CBLS with probabilistic smoothing (weight forgetting) + basin-hopping
                // perturbation — diversifies the long-run weight trajectory on plateau-heavy
                // landscapes where the bump-only schedule ossifies.
                LocalSearchWorkerConfig(
                    "cbls-smooth/ils-basin",
                    strategy = Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu),
                    restartPolicy = IteratedLocalSearchRestart(
                        populationSize = 3,
                        crossoverRate = 0.25,
                        perturbationKind = PerturbationKind.BasinHopping,
                        acceptance = AcceptanceCriterion.Improving,
                    ),
                    optimizeStrategy = Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu),
                ),
                // Adaptive probSAT: SOTA for the pure-Boolean / clausal SAT shape.
                LocalSearchWorkerConfig(
                    "adaptive-probsat/fixed",
                    ProbSat.adaptive(tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)),
                    FixedCadenceRestart(),
                ),
                // WalkSAT + configuration checking: cycle-breaking for tightly-coupled
                // (structured) constraint networks, paired with Luby restarts.
                LocalSearchWorkerConfig(
                    "walksat-cc/luby",
                    WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)),
                    LubyRestart(unit = 200),
                ),
                // Simulated annealing: temperature-driven drift through worse regions.
                LocalSearchWorkerConfig(
                    "sa/fixed",
                    SimulatedAnnealing(),
                    FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
                ),
                // CBLS plateau-buster: stall-gated same-domain pair swaps (score-only) +
                // primitive-only stalled noise (see [Cbls.stallSwapCap]). Closes the
                // reification plateaus on assignment-shaped instances (bacp/curriculum
                // class) that the flat repair pool never escapes; not the single-config
                // default because the hot-noise restriction costs feasibility on landscapes
                // that rely on randomly-taken factor compounds. Appended last so existing
                // small-portfolio mixes are unchanged; promoting it in palette order is a
                // measured follow-up.
                LocalSearchWorkerConfig(
                    "cbls-plateau/fixed",
                    strategy = Cbls(stallSwapCap = 16, tabu = cblsTabu),
                    restartPolicy = FixedCadenceRestart(),
                    optimizeStrategy = Cbls(stallSwapCap = 16, tabu = cblsTabu),
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
internal class LocalSearchPortfolio(val problem: Problem, val configs: List<LocalSearchWorkerConfig>) {
    init {
        require(configs.isNotEmpty()) { "Need at least one worker config" }
    }

    /** Shared atomic incumbent updated by workers when they find a better feasible sample.
     *  Consumers (e.g. the restart policy) read this to seed warm-restarts on stalled
     *  workers. Updated under a CAS so concurrent writes don't race past each other. */
    val sharedBest: AtomicReference<Sample?> = AtomicReference(null)

    /** Per-config [LocalSearchSession] for direct portfolio composition. */
    val workers: List<LocalSearchSession> = configs.map { cfg ->
        LocalSearchSolver(
            problem,
            strategy = cfg.strategy,
            optimizeStrategy = cfg.optimizeStrategy,
            restartPolicy = cfg.restartPolicy,
        ).session()
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
     * or any other [UnivariateBandit]; reading and updating
     * the bandit are the caller's responsibility — this slot is purely the shared
     * handle. Defaults to null (no cross-worker learning).
     */
    var restartBandit: UnivariateBandit? = null

    fun close() {
        workers.forEach { runCatching { it.close() } }
    }
}
