@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LocalSearchSession
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LubyRestart
import com.eignex.klause.solver.localsearch.PerturbationKind
import com.eignex.klause.solver.localsearch.RestartPolicy
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.MoveScoring
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
    /** Per-worker switch for the per-move invariant network (#153). Default on (when the
     *  model provides a definitional sweep). Off carves out a diversity niche: defined vars
     *  re-enter the move space, which is what cyclic-definitional successor encodings
     *  (prize-collecting's pos/next) need — under invariants their reified indicators are
     *  search-excluded and the dismantle chains can't thread (measured: chains solve pc 3/3
     *  without invariants, plateau at cost ≈3 with them). */
    val perMoveInvariants: Boolean = true,
) {
    companion object {
        private fun cblsTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

        private fun ilsBasin() = IteratedLocalSearchRestart(
            populationSize = 3,
            crossoverRate = 0.25,
            perturbationKind = PerturbationKind.BasinHopping,
            acceptance = AcceptanceCriterion.Improving,
        )

        /** A CBLS worker with the unified minimize path: [make] is invoked twice so the satisfy
         *  and optimize strategies are independent instances (Cbls carries per-search state). */
        private fun cblsWorker(
            label: String,
            restart: RestartPolicy,
            perMoveInvariants: Boolean = true,
            make: () -> Cbls,
        ) = LocalSearchWorkerConfig(
            label,
            make(),
            restart,
            optimizeStrategy = make(),
            perMoveInvariants = perMoveInvariants,
        )

        /**
         * The named pool of worker configs, **ordered by measured credit** so `diverse(n)` is
         * simply the first `n` entries — the `-p <n>` competition semantics. Factories, not
         * instances: strategies carry mutable per-search state (Cbls stall trackers, sinks), so
         * every portfolio slot must get fresh objects; sharing one config across two parallel
         * workers is a data race.
         *
         * Ranking source: the 2026-06-04 all-pool credit campaign — every config raced on every
         * mzn-bench optimization instance (10 s, 20 workers, per-worker attribution via
         * [com.eignex.klause.portfolio.Portfolio.improvementsAttributed]) — refined by a greedy
         * **marginal-contribution** pass: slots are awarded by how many instances a config adds
         * coverage on (`+uncovered`) given the slots above it, then by final-bests it would hold
         * (`+best`). Marginal beats raw credit: sa/fixed had 5 raw firsts and cbls-notabu 247 raw
         * improvements, but every instance either touched was already covered — both sit in the
         * tail. cbls/fixed stays first despite mid-pack optimization credit: the campaign
         * measured optimization only and CBLS remains the across-the-board satisfy winner (see
         * the CLI's strategy notes). Configs that earned no credit at all (cbls/luby,
         * cbls-tenure25, cbls-vnd, cbls-stallfast) were dropped.
         */
        private val poolFactories: List<Pair<String, () -> LocalSearchWorkerConfig>> = listOf(
            // The constraint-based workhorse; fastest first-incumbent (median 4 ms).
            "cbls/fixed" to { cblsWorker("cbls/fixed", FixedCadenceRestart()) { Cbls(tabu = cblsTabu()) } },
            // Adaptive probSAT: biggest marginal adder (+16 uncovered, +9 best) — many flattened
            // Challenge models expose a large boolean core.
            "adaptive-probsat/fixed" to {
                LocalSearchWorkerConfig(
                    "adaptive-probsat/fixed",
                    ProbSat.adaptive(tabu = cblsTabu()),
                    FixedCadenceRestart(),
                )
            },
            // Plateau-buster ([Cbls.stallSwapCap]) on the ILS basin-hopping restart: the best
            // plateau variant (+9 uncovered, +5 best).
            "cbls-plateau/ils-basin" to {
                cblsWorker("cbls-plateau/ils-basin", ilsBasin()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }
            },
            // Ejection chains ([Cbls.stallChainCap]) + targeted kick — the principled
            // plateau escape (#154). Sweep-off (perMoveInvariants = false): defined vars
            // re-enter the move space, the niche cyclic-definitional successor encodings
            // need — closes prize-collecting (3/3 in diag at ≤214k flips) where every
            // invariants-on config plateaus.
            // Deep-runway cadence: the measured pc dismantle threads at 21k–214k flips, so
            // the default 10k restart cadence cuts every walk short.
            "cbls-chain-noinv/fixed" to {
                cblsWorker(
                    "cbls-chain-noinv/fixed",
                    FixedCadenceRestart(maxFlipsBeforeRestart = 1_000_000),
                    perMoveInvariants = false,
                ) {
                    Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu())
                }
            },
            // Ejection chains on the ILS basin-hopping restart with invariants on (mirrors
            // the swap buster's best-variant pairing; +3 uncovered, +9 best-held at BOTH
            // campaign seeds — the most seed-stable adder in the pool).
            "cbls-chain/ils-basin" to {
                cblsWorker("cbls-chain/ils-basin", ilsBasin()) {
                    Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu())
                }
            },
            // Plateau-buster + smoothing (+5 uncovered, +2 best).
            "cbls-plateau-smooth/fixed" to {
                cblsWorker("cbls-plateau-smooth/fixed", FixedCadenceRestart()) {
                    Cbls(stallSwapCap = 16, smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
                }
            },
            // Plateau-buster on the fixed cadence (+3 uncovered incl. the bacp-class sole win).
            "cbls-plateau/fixed" to {
                cblsWorker("cbls-plateau/fixed", FixedCadenceRestart()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }
            },
            // Weight forgetting + basin hopping (+2 uncovered, +3 best).
            "cbls-smooth/ils-basin" to {
                cblsWorker("cbls-smooth/ils-basin", ilsBasin()) {
                    Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
                }
            },
            // Annealing + adaptive perturbation: the quality closer — adds no coverage but holds
            // the final best on 7 instances, the second-highest in the pool.
            "sa/adaptive-perturb" to {
                LocalSearchWorkerConfig("sa/adaptive-perturb", SimulatedAnnealing(), AdaptivePerturbationRestart())
            },
            // Patient stall cadence (+1 uncovered, +3 best, one sole win).
            "cbls-stallslow/fixed" to {
                cblsWorker("cbls-stallslow/fixed", FixedCadenceRestart()) {
                    Cbls(frontierAfterStall = 160, stallNoise = 0.2, tabu = cblsTabu())
                }
            },
            // Cold noise (+1 uncovered, +3 best).
            "cbls-lonoise/fixed" to {
                cblsWorker(
                    "cbls-lonoise/fixed",
                    FixedCadenceRestart(),
                ) { Cbls(noiseProbability = 0.01, tabu = cblsTabu()) }
            },
            // WalkSAT + configuration checking (+1 uncovered, +2 best; structured-SAT niche).
            "walksat-cc/luby" to {
                LocalSearchWorkerConfig(
                    "walksat-cc/luby",
                    WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)),
                    LubyRestart(unit = 200),
                )
            },
            // Hot noise (+1 uncovered, +1 best).
            "cbls-hinoise/fixed" to {
                cblsWorker(
                    "cbls-hinoise/fixed",
                    FixedCadenceRestart(),
                ) { Cbls(noiseProbability = 0.15, tabu = cblsTabu()) }
            },
            // --- tail: raw credit only; marginally redundant given the slots above ---
            // Tabu-free CBLS: high raw credit (4 firsts / 247 improvements) but +0 uncovered.
            "cbls-notabu/fixed" to {
                cblsWorker("cbls-notabu/fixed", FixedCadenceRestart()) { Cbls(tabu = TabuFilter.Disabled) }
            },
            // Plain annealing: 5 raw firsts, all on instances the slots above also solve.
            "sa/fixed" to {
                LocalSearchWorkerConfig(
                    "sa/fixed",
                    SimulatedAnnealing(),
                    FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
                )
            },
            // Aggressive swap cap (raw 1/2, 191 improvements).
            "cbls-plateau64/fixed" to {
                cblsWorker("cbls-plateau64/fixed", FixedCadenceRestart()) { Cbls(stallSwapCap = 64, tabu = cblsTabu()) }
            },
            // Raw (unweighted) scoring (raw 2/1).
            "cbls-raw/fixed" to {
                cblsWorker(
                    "cbls-raw/fixed",
                    FixedCadenceRestart(),
                ) { Cbls(scoring = MoveScoring.Raw, tabu = cblsTabu()) }
            },
            // Short tabu tenure (raw 2/0, 127 improvements).
            "cbls-tenure3/fixed" to {
                cblsWorker("cbls-tenure3/fixed", FixedCadenceRestart()) {
                    Cbls(tabu = TabuFilter(tenure = 3, aspiration = AspirationCriterion.OrImproving))
                }
            },
        )

        /**
         * Pool order by **cross-seed combined marginal credit** (2026-06-05, two campaigns at
         * seeds 1/2 on the #154 branch — 18 configs incl. the chain workers, 91 mzn-bench
         * optimization instances, 10 s; score = Σ uncovered + 0.5·Σ best-held; cbls/fixed
         * anchored first as the satisfy workhorse). Re-derive by re-running `bench run credit`
         * at two seeds (`-Dklause.bench.credit.seed`) and editing this one list. Notable:
         * the chain workers land #5/#6 on their first cross-seed campaign and absorb most of
         * the swap busters' coverage (plateau/fixed and plateau-smooth fell to the redundant
         * tail; plateau/ils-basin keeps a strong but high-variance contribution — #1 at one
         * seed, +0 uncovered at the other).
         */
        private val rankedOrder = listOf(
            "cbls/fixed", "cbls-plateau/ils-basin", "cbls-smooth/ils-basin", "sa/adaptive-perturb",
            "cbls-chain/ils-basin", "cbls-chain-noinv/fixed", "cbls-notabu/fixed", "cbls-lonoise/fixed",
            "adaptive-probsat/fixed", "cbls-tenure3/fixed", "cbls-stallslow/fixed", "sa/fixed",
            "cbls-plateau64/fixed", "walksat-cc/luby", "cbls-hinoise/fixed", "cbls-plateau-smooth/fixed",
            "cbls-plateau/fixed", "cbls-raw/fixed",
        )

        /** Labels of every config in the pool, in credit order. */
        val poolLabels: List<String> get() = rankedOrder

        /** Construct a fresh instance of the pool config named [label]. */
        fun byLabel(label: String): LocalSearchWorkerConfig =
            requireNotNull(poolFactories.firstOrNull { it.first == label }) {
                "unknown worker config '$label' (have ${poolFactories.map { it.first }})"
            }.second()

        /** One fresh instance of every pool config, in credit order. */
        fun pool(): List<LocalSearchWorkerConfig> = rankedOrder.map { byLabel(it) }

        /** The top-[count] prefix of the credit-ordered pool (wrapping past the pool size) —
         *  `-p <n>` maps straight onto this, so small portfolios get the measured-best workers
         *  first. Every slot is a fresh instance even when labels repeat. */
        fun diverse(count: Int): List<LocalSearchWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            return List(count) { byLabel(rankedOrder[it % rankedOrder.size]) }
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
