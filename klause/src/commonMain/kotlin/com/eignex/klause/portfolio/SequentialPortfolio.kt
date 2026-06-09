package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolveStats
import com.eignex.klause.solver.TerminationReason
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.Exp3Bandit
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Single-threaded, bandit-scheduled sibling of [Portfolio]. Where [Portfolio] races every
 * worker concurrently, this gives the **one core to one arm at a time**, picked by a kumulant
 * [UnivariateBandit] at each segment boundary, and hands the shared incumbent between segments.
 * On a single-core budget (the competition free/fixed track) this beats running the concurrent
 * portfolio, which would N-way oversubscribe the core; here the bandit concentrates the core on
 * whatever arm is currently making progress.
 *
 * An **arm** is a [PortfolioWorker] — the same heterogeneous unit the concurrent portfolio uses,
 * built by the same [PortfolioBuilder] — so the arm set is exactly one of the named scenarios
 * (`mixed` / `localSearchOnly` / `backtrackOnly`). A segment runs one arm for a growing time
 * slice; across segments the shared incumbent bound prunes backtrack arms (their
 * `objectiveBoundSupplier`) and the incumbent assignment warm-starts LS arms (their
 * `initialAssignment` seam, threaded through [PortfolioWorker.improvements]'s `warmStart`).
 *
 * **Reward** (folded into the bandit, normalised to `[0,1]` as the non-stationary policies expect)
 * is phase-aware so one signal serves both campaign goals:
 *  - before any incumbent exists: `1.0` if the segment found a first feasible solution, else `0.0`
 *    (drives the feasibility hunt);
 *  - once an incumbent exists: the segment's objective improvement, normalised by the largest
 *    improvement seen so far (drives anytime convergence).
 *
 * **Limitation (engine seam):** the backtrack engine has no pause/resume — each segment runs a
 * fresh search, so a backtrack arm re-learns clauses every time it is scheduled. Slices therefore
 * grow ([sliceGrowth]) so early segments sample the arms cheaply while later segments amortise
 * learning. A clause-preserving resumable session would remove the re-learning cost (see
 * `Session` "Future" notes) and is the natural follow-up.
 */
class SequentialPortfolio(
    /** The arms raced one-at-a-time; each carries its own engine, params, and objective form. */
    val workers: List<PortfolioWorker>,
    /** kumulant arm-selection policy; see [exp3] for the default non-stationary choice. */
    private val bandit: UnivariateBandit,
    /** First post-warmup time slice; subsequent slices grow by [sliceGrowth] up to [maxSliceMillis]. */
    private val baseSliceMillis: Long = 2_000,
    /** Cap on a single segment's time slice. */
    private val maxSliceMillis: Long = 60_000,
    /** Geometric growth applied to the slice after each post-warmup segment. */
    private val sliceGrowth: Double = 1.5,
    /** Round-robin warmup slice: each arm is forced once for this long before the bandit takes
     *  over, so a short deadline can't leave a winning arm at zero budget (EXP3 starvation). */
    private val warmupSliceMillis: Long = 1_000,
) : AutoCloseable {

    init {
        require(workers.isNotEmpty()) { "SequentialPortfolio must have at least one worker" }
        require(baseSliceMillis > 0 && maxSliceMillis >= baseSliceMillis) { "invalid slice bounds" }
        require(sliceGrowth >= 1.0) { "sliceGrowth must be ≥ 1.0" }
        require(warmupSliceMillis > 0) { "warmupSliceMillis must be > 0" }
    }

    /** A per-segment cancellation that fires when the global token fires or the slice elapses. */
    private fun sliceToken(global: Cancellation, sliceMillis: Long): Cancellation {
        val end = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        return Cancellation { global() || end.hasPassedNow() }
    }

    /**
     * Satisfaction: run arms in bandit-chosen segments until one returns a definitive Sat/Unsat
     * (a complete backtrack arm proves Unsat; a slice-truncated arm yields Unknown and the loop
     * moves on). Reward is `1.0` for a definitive verdict, `0.0` for an inconclusive slice.
     */
    fun solve(cancellation: Cancellation = Cancellation.Never): SolveResult {
        var stats = SolveStats.EMPTY
        var slice = baseSliceMillis
        var segment = 0
        while (!cancellation()) {
            val warming = segment < workers.size
            val arm = if (warming) segment else bandit.choose()
            val sliceMs = if (warming) warmupSliceMillis else slice
            val r = runCatching { workers[arm].solve(sliceToken(cancellation, sliceMs)) }.getOrNull()
            if (r != null) stats = stats.mergedWith(r.stats)
            val definitive = r is SolveResult.Sat || r is SolveResult.Unsat
            bandit.update(arm, if (definitive) 1.0 else 0.0)
            when (r) {
                is SolveResult.Sat -> return r.copy(stats = stats)
                is SolveResult.Unsat -> return r.copy(stats = stats)
                else -> Unit
            }
            if (!warming) slice = (slice * sliceGrowth).toLong().coerceAtMost(maxSliceMillis)
            segment++
        }
        return SolveResult.Unknown(TerminationReason.Cancelled, stats)
    }

    /**
     * Branch-and-bound: run arms in bandit-chosen segments, carrying one shared incumbent. Each
     * segment streams against its arm's own objective representation, sees the shared bound
     * (backtrack prunes on it) and the incumbent assignment (LS warm-starts from it). Returns
     * [MinimizeResult.Optimal]/[MinimizeResult.Infeasible] only when an arm exhausts its search
     * (a `SearchExhausted` terminal that the slice did not truncate), otherwise the best incumbent
     * as [MinimizeResult.BestFound]. [onIncumbent] fires once per strict global improvement, for
     * anytime telemetry.
     */
    fun minimize(
        cancellation: Cancellation = Cancellation.Never,
        onIncumbent: ((MinimizeResult) -> Unit)? = null,
    ): MinimizeResult {
        var bound = Double.POSITIVE_INFINITY
        var best: Sample? = null
        var rewardScale = 0.0
        var stats = SolveStats.EMPTY
        var slice = baseSliceMillis
        var segment = 0
        val readBound = { bound }

        while (!cancellation()) {
            // Round-robin warmup: force every arm once (at the short warmup slice) before the
            // bandit free-selects, so a backtrack arm a COP needs can't be starved to zero budget.
            val warming = segment < workers.size
            val arm = if (warming) segment else bandit.choose()
            val sliceMs = if (warming) warmupSliceMillis else slice
            val hadIncumbent = best != null
            val before = bound
            var terminal: MinimizeResult? = null
            runCatching {
                for (r in workers[arm].improvements(readBound, sliceToken(cancellation, sliceMs), warmStart = best)) {
                    terminal = r
                    if (r is MinimizeResult.WithSample && r.objectiveValue < bound) {
                        bound = r.objectiveValue
                        best = r.sample
                        onIncumbent?.invoke(r)
                    }
                }
            }
            terminal?.let { stats = stats.mergedWith(it.stats) }

            val reward = if (!hadIncumbent) {
                if (best != null) 1.0 else 0.0
            } else {
                val improvement = before - bound
                if (improvement > rewardScale) rewardScale = improvement
                if (rewardScale > 0.0) (improvement / rewardScale).coerceIn(0.0, 1.0) else 0.0
            }
            bandit.update(arm, reward)

            if (isExhausted(terminal)) {
                val exhaustedBest = best
                return if (exhaustedBest != null) {
                    MinimizeResult.Optimal(exhaustedBest, bound, stats)
                } else {
                    MinimizeResult.Infeasible(stats = stats)
                }
            }
            if (!warming) slice = (slice * sliceGrowth).toLong().coerceAtMost(maxSliceMillis)
            segment++
        }
        val b = best
        return if (b != null) {
            MinimizeResult.BestFound(b, bound, TerminationReason.BudgetExhausted, stats)
        } else {
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
        }
    }

    /** A segment terminal that proves the arm covered its whole space (so the incumbent is optimal,
     *  or there is no solution). A slice-truncated run reports Cancelled/BudgetExhausted instead, so
     *  `SearchExhausted` reliably distinguishes a genuine exhaustion from a timed-out segment. */
    private fun isExhausted(terminal: MinimizeResult?): Boolean = when (terminal) {
        is MinimizeResult.Optimal -> true
        is MinimizeResult.Infeasible -> true
        is MinimizeResult.BestFound -> terminal.reason == TerminationReason.SearchExhausted
        is MinimizeResult.Unknown -> terminal.reason == TerminationReason.SearchExhausted
        null -> false
    }

    override fun close() {
        workers.forEach { runCatching { it.close() } }
    }

    /** Named-policy convenience factories. The primary constructor takes any kumulant
     *  [UnivariateBandit], so these are just conveniences for the common policies — add more (or
     *  call the constructor directly) for `KlUcb`, `Boltzmann`, `RouletteWheel`, etc. */
    companion object {
        /** [Exp3Bandit] arm selection (non-stationary — the right fit for a reward that shifts as
         *  the search moves from feasibility-finding to bound-improving). */
        fun exp3(
            workers: List<PortfolioWorker>,
            seed: Long = 0L,
            gamma: Double = 0.1,
            eta: Double = 0.1,
            baseSliceMillis: Long = 2_000,
            maxSliceMillis: Long = 60_000,
            sliceGrowth: Double = 1.5,
            warmupSliceMillis: Long = 1_000,
        ): SequentialPortfolio = withBandit(
            workers,
            Exp3Bandit(workers.size, eta, gamma, Random(seed)),
            baseSliceMillis,
            maxSliceMillis,
            sliceGrowth,
            warmupSliceMillis,
        )

        /** UCB1 arm selection (stationary, deterministic given the seed) — the alternative to
         *  [exp3] when arm utility is roughly fixed over the run. */
        fun ucb1(
            workers: List<PortfolioWorker>,
            seed: Long = 0L,
            alpha: Double = 1.0,
            baseSliceMillis: Long = 2_000,
            maxSliceMillis: Long = 60_000,
            sliceGrowth: Double = 1.5,
            warmupSliceMillis: Long = 1_000,
        ): SequentialPortfolio = withBandit(
            workers,
            MultiArmedBandit(workers.size, UCB1(alpha = alpha), Random(seed)),
            baseSliceMillis,
            maxSliceMillis,
            sliceGrowth,
            warmupSliceMillis,
        )

        private fun withBandit(
            workers: List<PortfolioWorker>,
            bandit: UnivariateBandit,
            baseSliceMillis: Long,
            maxSliceMillis: Long,
            sliceGrowth: Double,
            warmupSliceMillis: Long,
        ): SequentialPortfolio = SequentialPortfolio(
            workers = workers,
            bandit = bandit,
            baseSliceMillis = baseSliceMillis,
            maxSliceMillis = maxSliceMillis,
            sliceGrowth = sliceGrowth,
            warmupSliceMillis = warmupSliceMillis,
        )
    }
}
