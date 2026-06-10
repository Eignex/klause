@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
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
 *  - **Per-worker strategy selection** (`config`): each worker gets a distinct
 *    `(Strategy, RestartPolicy)` pair from the supplied [LocalSearchWorkerConfig] list,
 *    so the portfolio explores algorithmically-orthogonal trajectories in parallel.
 *  - **Best-feasible sharing** (`sharedBest`): workers publish their incumbent samples
 *    into a shared atomic reference exposed back through the [LocalSearchSession]'s
 *    warm-start hook; on restart, a worker that hasn't found anything yet anchors to
 *    the global best instead of a fresh random assignment.
 *  - **Shared kumulant stats** (`restartBandit`): an optional univariate bandit over the
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
    override val label: String,
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
) : WorkerConfig {

    /** Build an LS worker: its [LocalSearchSolver] session (with the per-move invariant network when
     *  a [definitionalSweep] is present) + λ-shaped params, exposing the warm-start seam so a
     *  [SequentialPortfolio] can resume a segment from the shared incumbent. Label is `ls/<label>`. */
    override fun materialize(
        problem: Problem,
        index: Int,
        seed: Long,
        lsLambda: Double,
        lsObjective: Objective?,
        linearObjective: Objective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    ): PortfolioWorker {
        val session = LocalSearchSolver(
            problem,
            strategy = strategy,
            optimizeStrategy = optimizeStrategy,
            restartPolicy = restartPolicy,
            definitionalSweep = definitionalSweep,
            perMoveInvariants = definitionalSweep != null && perMoveInvariants,
        ).session()
        val workerLabel = "ls/$label"
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
        )
        return PortfolioWorker.of(
            workerLabel,
            session,
            params,
            objective = lsObjective ?: linearObjective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
        )
    }

    companion object {
        private fun cblsTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

        private fun ilsBasin() = IteratedLocalSearchRestart(
            populationSize = 3,
            crossoverRate = 0.25,
            perturbationKind = PerturbationKind.BasinHopping,
            acceptance = AcceptanceCriterion.Improving,
        )

        /** ILS basin-hopping whose accept/reject is driven by the contextual acceptance bandit
         *  (#8) — learns when drifting through worse optima pays off, rather than the fixed
         *  improving-only rule. Fresh bandit per slot. */
        private fun ilsBandit() = IteratedLocalSearchRestart(
            populationSize = 3,
            crossoverRate = 0.25,
            perturbationKind = PerturbationKind.BasinHopping,
            acceptanceBandit = IteratedLocalSearchRestart.acceptanceBandit(),
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
         * Fresh [LocalSearchWorkerConfig] for a typed [LsArm] — the catalog's single factory.
         * Exhaustive `when` so every arm in [LsArm] must have a factory (and conversely every
         * factory a typed arm); the per-arm rationale below is the credit-campaign provenance.
         *
         * Factories, not shared instances: strategies carry mutable per-search state (Cbls stall
         * trackers, sinks), so every portfolio slot must get fresh objects — sharing one across two
         * parallel workers is a data race.
         */
        private fun make(arm: LsArm): LocalSearchWorkerConfig = when (arm) {
            // The constraint-based workhorse; fastest first-incumbent (median 4 ms).
            LsArm.CblsFixed -> cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(tabu = cblsTabu()) }

            // Adaptive probSAT: biggest marginal adder (+16 uncovered, +9 best) — many flattened
            // Challenge models expose a large boolean core.
            LsArm.AdaptiveProbsatFixed ->
                LocalSearchWorkerConfig(arm.label, ProbSat.adaptive(tabu = cblsTabu()), FixedCadenceRestart())

            // Plateau-buster ([Cbls.stallSwapCap]) on the ILS basin-hopping restart: the best
            // plateau variant (+9 uncovered, +5 best).
            LsArm.CblsPlateauIlsBasin ->
                cblsWorker(arm.label, ilsBasin()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

            // Ejection chains ([Cbls.stallChainCap]) + targeted kick — the principled plateau escape
            // (#154). Sweep-off (perMoveInvariants = false): defined vars re-enter the move space,
            // the niche cyclic-definitional successor encodings need — closes prize-collecting (3/3
            // in diag at ≤214k flips) where every invariants-on config plateaus. Deep-runway cadence:
            // the measured pc dismantle threads at 21k–214k flips, so the default 10k cadence cuts
            // every walk short.
            LsArm.CblsChainNoinvFixed -> cblsWorker(
                arm.label,
                FixedCadenceRestart(maxFlipsBeforeRestart = 1_000_000),
                perMoveInvariants = false,
            ) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

            // Ejection chains on the ILS basin-hopping restart with invariants on (mirrors the swap
            // buster's best-variant pairing; +3 uncovered, +9 best-held at BOTH campaign seeds — the
            // most seed-stable adder in the pool).
            LsArm.CblsChainIlsBasin ->
                cblsWorker(arm.label, ilsBasin()) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

            // Plateau-buster + smoothing (+5 uncovered, +2 best).
            LsArm.CblsPlateauSmoothFixed -> cblsWorker(arm.label, FixedCadenceRestart()) {
                Cbls(stallSwapCap = 16, smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
            }

            // Plateau-buster on the fixed cadence (+3 uncovered incl. the bacp-class sole win).
            LsArm.CblsPlateauFixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

            // Weight forgetting + basin hopping (+2 uncovered, +3 best).
            LsArm.CblsSmoothIlsBasin ->
                cblsWorker(arm.label, ilsBasin()) { Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu()) }

            // Annealing + adaptive perturbation: the quality closer — adds no coverage but holds the
            // final best on 7 instances, the second-highest in the pool.
            LsArm.SaAdaptivePerturb ->
                LocalSearchWorkerConfig(arm.label, SimulatedAnnealing(), AdaptivePerturbationRestart())

            // Patient stall cadence (+1 uncovered, +3 best, one sole win).
            LsArm.CblsStallslowFixed -> cblsWorker(arm.label, FixedCadenceRestart()) {
                Cbls(frontierAfterStall = 160, stallNoise = 0.2, tabu = cblsTabu())
            }

            // Cold noise (+1 uncovered, +3 best).
            LsArm.CblsLonoiseFixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.01, tabu = cblsTabu()) }

            // WalkSAT + configuration checking (+1 uncovered, +2 best; structured-SAT niche).
            LsArm.WalksatCcLuby -> LocalSearchWorkerConfig(
                arm.label,
                WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)),
                LubyRestart(unit = 200),
            )

            // Hot noise (+1 uncovered, +1 best).
            LsArm.CblsHinoiseFixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.15, tabu = cblsTabu()) }

            // --- tail: raw credit only; marginally redundant given the arms above ---
            // Tabu-free CBLS: high raw credit (4 firsts / 247 improvements) but +0 uncovered.
            LsArm.CblsNotabuFixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(tabu = TabuFilter.Disabled) }

            // Plain annealing: 5 raw firsts, all on instances the arms above also solve.
            LsArm.SaFixed -> LocalSearchWorkerConfig(
                arm.label,
                SimulatedAnnealing(),
                FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
            )

            // Aggressive swap cap (raw 1/2, 191 improvements).
            LsArm.CblsPlateau64Fixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 64, tabu = cblsTabu()) }

            // Raw (unweighted) scoring (raw 2/1).
            LsArm.CblsRawFixed ->
                cblsWorker(arm.label, FixedCadenceRestart()) { Cbls(scoring = MoveScoring.Raw, tabu = cblsTabu()) }

            // Short tabu tenure (raw 2/0, 127 improvements).
            LsArm.CblsTenure3Fixed -> cblsWorker(arm.label, FixedCadenceRestart()) {
                Cbls(tabu = TabuFilter(tenure = 3, aspiration = AspirationCriterion.OrImproving))
            }

            // Contextual-bandit ILS acceptance (#8): CBLS on a basin-hopping ILS restart whose
            // accept/reject is learned.
            LsArm.CblsIlsBandit -> cblsWorker(arm.label, ilsBandit()) { Cbls(tabu = cblsTabu()) }

            // Bandit-adaptive probSAT (#8): a UCB1 bandit picks the cb noise schedule per session.
            LsArm.ProbsatBanditFixed ->
                LocalSearchWorkerConfig(arm.label, ProbSat.bandit(tabu = cblsTabu()), FixedCadenceRestart())
        }

        /**
         * Pool order by **cross-seed combined marginal credit** (2026-06-05, two campaigns at
         * seeds 1/2 on the #154 branch — 18 configs incl. the chain workers, 91 mzn-bench
         * optimization instances, 10 s; score = Σ uncovered + 0.5·Σ best-held; cbls/fixed
         * anchored first as the satisfy workhorse). [diverse] takes a prefix of this, so `-p <n>`
         * gets the measured-best arms first. Re-derive by re-running `bench run credit` at two
         * seeds (`-Dklause.bench.credit.seed`) and editing this one list. Notable: the chain workers
         * land #5/#6 on their first cross-seed campaign and absorb most of the swap busters'
         * coverage; plateau/ils-basin keeps a strong but high-variance contribution (#1 at one seed,
         * +0 uncovered at the other).
         */
        private val ranked: List<LsArm> = listOf(
            LsArm.CblsFixed, LsArm.CblsPlateauIlsBasin, LsArm.CblsSmoothIlsBasin, LsArm.SaAdaptivePerturb,
            LsArm.CblsChainIlsBasin, LsArm.CblsChainNoinvFixed, LsArm.CblsNotabuFixed, LsArm.CblsLonoiseFixed,
            LsArm.AdaptiveProbsatFixed, LsArm.CblsTenure3Fixed, LsArm.CblsStallslowFixed, LsArm.SaFixed,
            LsArm.CblsPlateau64Fixed, LsArm.WalksatCcLuby, LsArm.CblsHinoiseFixed, LsArm.CblsPlateauSmoothFixed,
            LsArm.CblsPlateauFixed, LsArm.CblsRawFixed,
            // Bandit candidates (#8); kept last so the default diverse(N) prefix is unchanged. The
            // #9-lite credit pass kept these two (each held a best); the third, the LS move bandit,
            // was a dud and was removed.
            LsArm.CblsIlsBandit, LsArm.ProbsatBanditFixed,
        )

        /** Resolve a catalog label to its typed [LsArm] — the single string boundary, for the CLI /
         *  campaign config knob (`-Dklause.…configs=`). */
        private fun fromLabel(label: String): LsArm = LsArm.entries.firstOrNull { it.label == label }
            ?: error("unknown LS worker config '$label' (have ${LsArm.entries.joinToString { it.label }})")

        /** A fresh instance of the pool config named [label] (the string boundary; see [fromLabel]). */
        fun byLabel(label: String): LocalSearchWorkerConfig = make(fromLabel(label))

        /** One fresh instance of every pool config, in credit order. */
        fun pool(): List<LocalSearchWorkerConfig> = ranked.map { make(it) }

        /** The top-[count] prefix of the credit-ordered pool (wrapping past the pool size) — `-p <n>`
         *  maps straight onto this. Every slot is a fresh instance even when arms repeat. */
        fun diverse(count: Int): List<LocalSearchWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            return List(count) { make(ranked[it % ranked.size]) }
        }
    }
}

/**
 * Typed identity of every LS pool arm — the catalog's keys. [LocalSearchWorkerConfig.diverse] /
 * [LocalSearchWorkerConfig.pool] order and instantiate these via the exhaustive
 * `LocalSearchWorkerConfig.make`; [label] is the external name (CLI / campaign / telemetry).
 */
internal enum class LsArm(val label: String) {
    CblsFixed("cbls/fixed"),
    AdaptiveProbsatFixed("adaptive-probsat/fixed"),
    CblsPlateauIlsBasin("cbls-plateau/ils-basin"),
    CblsChainNoinvFixed("cbls-chain-noinv/fixed"),
    CblsChainIlsBasin("cbls-chain/ils-basin"),
    CblsPlateauSmoothFixed("cbls-plateau-smooth/fixed"),
    CblsPlateauFixed("cbls-plateau/fixed"),
    CblsSmoothIlsBasin("cbls-smooth/ils-basin"),
    SaAdaptivePerturb("sa/adaptive-perturb"),
    CblsStallslowFixed("cbls-stallslow/fixed"),
    CblsLonoiseFixed("cbls-lonoise/fixed"),
    WalksatCcLuby("walksat-cc/luby"),
    CblsHinoiseFixed("cbls-hinoise/fixed"),
    CblsNotabuFixed("cbls-notabu/fixed"),
    SaFixed("sa/fixed"),
    CblsPlateau64Fixed("cbls-plateau64/fixed"),
    CblsRawFixed("cbls-raw/fixed"),
    CblsTenure3Fixed("cbls-tenure3/fixed"),
    CblsIlsBandit("cbls/ils-bandit"),
    ProbsatBanditFixed("probsat-bandit/fixed"),
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
