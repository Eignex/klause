package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.Exp3Bandit
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Single-threaded, bandit-scheduled sibling of `Portfolio`. Where `Portfolio` races every
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
 * **Resumable backtrack arms:** a backtrack arm exposes a [ResumableSearch]
 * ([PortfolioWorker.newResumableSearch]); [minimize] holds one handle per such arm and *resumes* it
 * each time the bandit reschedules it, so the arm continues its exact search — live learned clauses,
 * DFS trail, heuristics, incumbent and LP warm-start caches all intact — instead of cold-restarting
 * and re-deriving its clauses every segment. Local-search arms have no handle (null), so they run a
 * fresh slice warm-started from the shared incumbent. Slices still grow
 * ([sliceGrowth]) so early segments sample the arms cheaply while later segments dig deeper.
 *
 * **Re-seeding plateaued arms ([reseedStaleThreshold]):** pure resume keeps one persistent DFS trail,
 * which converges fast but forgoes the bound-guided re-exploration a per-segment cold restart buys
 * (each fresh search re-descends under a tighter bound). To recover it without giving up
 * convergence, a resumable arm that fails to improve the incumbent for several consecutive segments has
 * its handle discarded and rebuilt fresh on the next schedule — re-descending from the root under the
 * now-tighter bound, with the pool's learned clauses re-imported.
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
    /**
     * Nodes a resumable arm's first segment may explore; later segments grow by [sliceGrowth] up to
     * [maxSliceNodes], mirroring the millisecond schedule.
     *
     * Resumable arms are sliced by nodes and never by the clock. A segment bounded by time pauses
     * somewhere different on every run, and since the search resumes from wherever it stopped, every
     * counter a solve reports inherits that — two identical invocations are not comparable. A segment
     * bounded by nodes pauses at the same point in the same tree every time. The whole-solve deadline
     * still applies, so this cannot overrun it.
     *
     * Local-search arms keep the millisecond schedule: they run a fresh search per segment rather than
     * pausing one, so a node budget has nothing to count.
     */
    private val baseSliceNodes: Long = 5_000,
    /** Cap on a single resumable segment's node budget. */
    private val maxSliceNodes: Long = 150_000,
    /** Round-robin warmup slice: each arm is forced once for this long before the bandit takes
     *  over, so a short deadline can't leave a winning arm at zero budget (EXP3 starvation). */
    private val warmupSliceMillis: Long = 1_000,
    /**
     * Diversification for resumable backtrack arms: after this many consecutive
     * scheduled segments in which a resumable arm fails to improve the shared incumbent, its search
     * handle is discarded so the next schedule opens a **fresh** one. The fresh search re-descends from
     * the root under the now-tighter shared bound and re-imports the pool's learned clauses — recovering
     * the bound-guided re-exploration the pre-resume per-segment cold restart gave (which resume's single
     * persistent trail had traded away, regressing value on plateau-prone instances like `cargo`), while
     * keeping resume's fast initial convergence (it only fires after a plateau) and clause retention.
     * `0` disables re-seeding (pure resume). Only resumable (backtrack) arms are affected; LS arms
     * already run a fresh warm-started slice each segment.
     */
    private val reseedStaleThreshold: Int = 3,
) : PortfolioExecutor {

    init {
        require(workers.isNotEmpty()) { "SequentialPortfolio must have at least one worker" }
        require(baseSliceMillis > 0 && maxSliceMillis >= baseSliceMillis) { "invalid slice bounds" }
        require(sliceGrowth >= 1.0) { "sliceGrowth must be ≥ 1.0" }
        require(warmupSliceMillis > 0) { "warmupSliceMillis must be > 0" }
        require(reseedStaleThreshold >= 0) { "reseedStaleThreshold must be ≥ 0" }
        require(baseSliceNodes > 0 && maxSliceNodes >= baseSliceNodes) { "invalid node slice bounds" }
    }

    /** A per-segment cancellation that fires when the slice elapses or the global token fires. Built from
     *  [Cancellation.until] (not a bare predicate) so it carries the slice deadline — an arm can then size
     *  a sub-phase as a fraction of its slice via [Cancellation.shorten] (the ALNS bootstrap). */
    private fun sliceToken(global: Cancellation, sliceMillis: Long): Cancellation =
        Cancellation.until(TimeSource.Monotonic.markNow() + sliceMillis.milliseconds) or global

    /**
     * Satisfaction: run arms in bandit-chosen segments until one returns a definitive Sat/Unsat
     * (a complete backtrack arm proves Unsat; a slice-truncated arm yields Unknown and the loop
     * moves on). Reward is `1.0` for a definitive verdict, `0.0` for an inconclusive slice.
     */
    override fun solve(cancellation: Cancellation): SolveResult {
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
     * as [MinimizeResult.BestFound]. `onImprovement` fires once per strict global improvement, tagged
     * with the arm that produced it (the segment is single-armed, so attribution is exact) and the
     * elapsed time — the anytime/credit telemetry, identical in shape to the parallel executor's.
     */
    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        var bound = Double.POSITIVE_INFINITY
        var best: Sample? = null
        var rewardScale = 0.0
        var slice = baseSliceMillis
        var sliceNodes = baseSliceNodes
        var segment = 0
        val start = TimeSource.Monotonic.markNow()
        // The label of the arm running the current segment — single-threaded, so it is unambiguous
        // for every improvement [accept] folds while that segment is active.
        var armLabel = workers.first().label
        // The arm identity of the active segment, tracked alongside [armLabel] for attribution. The
        // sequential track never replicates, so armId == the worker's position here — nothing pools.
        var armId = workers.first().armId
        val readBound = { bound }
        // One resumable handle per backtrack arm, opened lazily on the arm's first segment and resumed
        // on every later one. LS arms stay null and run a fresh warm-started slice each time.
        val handles = arrayOfNulls<ResumableSearch>(workers.size)
        // Per-arm counters. A resumable arm's handle carries them cumulatively, so its entry is replaced
        // each segment rather than accumulated; a non-resumable arm runs a fresh search per segment, so
        // its terminal verdicts are merged. Folding only terminal verdicts loses every arm the deadline
        // paused instead of finishing — which, under a wall clock, is usually all of them.
        val perArm = arrayOfNulls<SolveStats>(workers.size)
        // Consecutive non-improving segments per arm; drives re-seeding (see [reseedStaleThreshold]).
        val staleSegments = IntArray(workers.size)

        // Fold a strictly-improving incumbent into the shared bound + fire the telemetry callback,
        // attributing it to the arm of the active segment ([armLabel]).
        fun accept(r: MinimizeResult.WithSample) {
            if (r.objectiveValue < bound) {
                bound = r.objectiveValue
                best = r.sample
                onImprovement?.invoke(AttributedImprovement(armLabel, armId, start.elapsedNow(), r))
            }
        }

        while (!cancellation()) {
            // Round-robin warmup: force every arm once (at the short warmup slice) before the
            // bandit free-selects, so a backtrack arm a COP needs can't be starved to zero budget.
            val warming = segment < workers.size
            val arm = if (warming) segment else bandit.choose()
            val sliceMs = if (warming) warmupSliceMillis else slice
            val hadIncumbent = best != null
            val before = bound
            val worker = workers[arm]
            armLabel = worker.label
            armId = worker.armId
            val handle = handles[arm] ?: worker.newResumableSearch(readBound)?.also { handles[arm] = it }
            var terminal: MinimizeResult? = null
            if (handle != null) {
                // Resume the arm's search for this slice; a terminal verdict means it finished, null
                // means the slice elapsed (search paused, state retained for the next reschedule).
                terminal = runCatching {
                    handle.runSlice(cancellation, sliceMs, sliceNodes) { accept(it) }
                }.getOrNull()
            } else {
                // A non-resumable arm runs a fresh search per segment rather than pausing one, so it
                // cannot take a node budget. Under node slicing it therefore gets no per-segment
                // deadline: a wall-clock one here would decide where this arm stops, and every counter
                // the run reports follows from that. Its own node allowance still bounds it, and the
                // whole-solve deadline still applies.
                val armToken = sliceToken(cancellation, sliceMs)
                runCatching {
                    for (r in worker.improvements(readBound, armToken, warmStart = best)) {
                        terminal = r
                        if (r is MinimizeResult.WithSample) accept(r)
                    }
                }
            }
            if (handle != null) {
                perArm[arm] = handle.stats
            } else {
                terminal?.let { perArm[arm] = (perArm[arm] ?: SolveStats.EMPTY).mergedWith(it.stats) }
            }

            val improvement = before - bound
            val reward = if (!hadIncumbent) {
                if (best != null) 1.0 else 0.0
            } else {
                if (improvement > rewardScale) rewardScale = improvement
                if (rewardScale > 0.0) (improvement / rewardScale).coerceIn(0.0, 1.0) else 0.0
            }
            bandit.update(arm, reward)

            // Re-seed a plateaued resumable arm: after enough consecutive non-improving segments, drop
            // its handle so the next schedule re-descends from the root under the tighter bound with the
            // pool's clauses re-imported — restoring diversification without losing convergence.
            // Guards keep it from disrupting productive search: only once an incumbent exists (the
            // feasibility hunt is never reset), and never on a segment that already returned a terminal
            // verdict (a completed optimality / infeasibility proof short-circuits to the return below).
            if (handle != null && terminal == null && best != null) {
                if (improvement > 0.0) {
                    staleSegments[arm] = 0
                } else if (reseedStaleThreshold > 0 && ++staleSegments[arm] >= reseedStaleThreshold) {
                    runCatching { handle.close() }
                    handles[arm] = null
                    staleSegments[arm] = 0
                }
            }

            // A clean segment exhaustion ends the run: any incumbent is optimal, else infeasible.
            if (PortfolioReduction.isExhausted(terminal)) {
                return PortfolioReduction.terminal(best, bound, dirty = false, foldArms(perArm))
            }
            if (!warming) {
                slice = (slice * sliceGrowth).toLong().coerceAtMost(maxSliceMillis)
                sliceNodes = (sliceNodes * sliceGrowth).toLong().coerceAtMost(maxSliceNodes)
            }
            segment++
        }
        // Cancellation stopped a still-open search: keep the incumbent (BestFound) or report Unknown.
        return PortfolioReduction.terminal(best, bound, dirty = true, foldArms(perArm))
    }

    /** The pool's total counters: every arm that did work, whether or not it reached a verdict. */
    private fun foldArms(perArm: Array<SolveStats?>): SolveStats =
        perArm.filterNotNull().fold(SolveStats.EMPTY) { acc, s -> acc.mergedWith(s) }

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
            reseedStaleThreshold: Int = 3,
            baseSliceNodes: Long = 5_000,
        ): SequentialPortfolio = withBandit(
            workers,
            Exp3Bandit(workers.size, eta, gamma, Random(seed)),
            baseSliceMillis,
            maxSliceMillis,
            sliceGrowth,
            warmupSliceMillis,
            reseedStaleThreshold,
            baseSliceNodes,
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
            reseedStaleThreshold: Int = 3,
        ): SequentialPortfolio = withBandit(
            workers,
            MultiArmedBandit(workers.size, UCB1(alpha = alpha), Random(seed)),
            baseSliceMillis,
            maxSliceMillis,
            sliceGrowth,
            warmupSliceMillis,
            reseedStaleThreshold,
        )

        private fun withBandit(
            workers: List<PortfolioWorker>,
            bandit: UnivariateBandit,
            baseSliceMillis: Long,
            maxSliceMillis: Long,
            sliceGrowth: Double,
            warmupSliceMillis: Long,
            reseedStaleThreshold: Int,
            baseSliceNodes: Long = 5_000,
        ): SequentialPortfolio = SequentialPortfolio(
            workers = workers,
            bandit = bandit,
            baseSliceMillis = baseSliceMillis,
            maxSliceMillis = maxSliceMillis,
            sliceGrowth = sliceGrowth,
            warmupSliceMillis = warmupSliceMillis,
            reseedStaleThreshold = reseedStaleThreshold,
            baseSliceNodes = baseSliceNodes,
        )
    }
}
