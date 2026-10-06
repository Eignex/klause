package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.bound
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.bandit.UnivariateBandit
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
 * (`mixed` / `localSearchOnly` / `backtrackOnly`). An arm with a deterministic work counter runs
 * one counted segment; across segments the shared incumbent bound prunes backtrack arms (their
 * `objectiveBoundSupplier`) and the incumbent assignment warm-starts LS arms (their
 * `initialAssignment` seam, threaded through [PortfolioWorker.improvements]'s `warmStart`).
 *
 * **Reward** (folded into the bandit in `[0, 1]`) comes from a [RewardLedger]: each arm is credited for what it
 * contributed and scored on the rate it earned that at, per unit of work, against the rest of the pool's rate.
 * Before any incumbent exists the credit is a first feasible solution, which drives the feasibility hunt; once
 * one exists it is the objective improvement, which drives anytime convergence. A rise in the pool's proven
 * lower bound is credited to the arm that ran, so an arm closing the gap from below earns as one closing it from
 * above does. Progress short of a solution earns credit in both: variables a backtrack arm fixes at its root,
 * and a local-search arm lowering the record violation ([ProgressCredit]). The ledger starts a new phase at the
 * first incumbent, since the rates a feasibility hunt earned say nothing about who improves one. An arm is also
 * credited when another arm uses what it shared: an imported clause in a conflict or unit, an imported cut
 * selected into a relaxation, an imported bound tightening a domain ([ContributionTally]).
 *
 * **Resumable backtrack arms:** a backtrack arm exposes a [ResumableSearch]
 * ([PortfolioWorker.newResumableSearch]); [minimize] holds one handle per such arm and *resumes* it
 * each time the bandit reschedules it, so the arm continues its exact search — live learned clauses,
 * DFS trail, heuristics, incumbent and LP warm-start caches all intact — instead of cold-restarting
 * and re-deriving its clauses every segment. Local-search arms have no handle (null), so they run a
 * fresh slice warm-started from the shared incumbent. A resumable arm runs a constant [baseSliceWork] each
 * segment: resuming costs nothing, so short segments give the bandit many decisions at no loss of depth. A
 * restarting arm's segments grow ([sliceGrowth]) so it can dig deeper than one short restart reaches.
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
    /** kumulant arm-selection policy over rewards in `[0, 1]`; see [thompson] for the default. */
    private val bandit: UnivariateBandit,
    /** First time slice for an arm with neither a work counter nor a resumable handle. */
    private val baseSliceMillis: Long = 2_000,
    /** Cap on a single segment's time slice. */
    private val maxSliceMillis: Long = 60_000,
    /** Geometric growth applied to a restarting arm's slice after each segment. */
    private val sliceGrowth: Double = 1.5,
    /**
     * Work each segment of a resumable arm spends, and the first segment of a counted local-search arm; a local-search
     * arm's later segments grow by [sliceGrowth] up to [maxSliceWork].
     *
     * Work is measured in node-equivalents, one unit for every arm whatever its engine. A resumable
     * backtrack arm spends one per search node plus its LP work at the rate
     * [com.eignex.klause.solver.ResumableSearch.runSlice] charges it. A local-search or ALNS arm
     * ([PortfolioWorker.acceptsInstructionBudget]) spends one per [lsInstructionsPerWork] instructions of
     * its counted allowance; ALNS spends it across its own outer destroy/repair loop instead of one inner
     * solve (see [com.eignex.klause.meta.alns.Alns]'s class KDoc). A common unit is what lets one
     * schedule give every arm a comparable turn.
     *
     * Counted arms are never sliced by the clock. A segment bounded by time pauses somewhere different on
     * every run, and since the search resumes from wherever it stopped, every counter a solve reports
     * inherits that — two identical invocations are not comparable. A segment bounded by work pauses at
     * the same point every time. The whole-solve deadline still applies, so this cannot overrun it.
     */
    private val baseSliceWork: Long = 5_000,
    /** Cap on a single local-search segment's work. */
    private val maxSliceWork: Long = 150_000,
    /** Local-search instructions that cost as much as one search node; see `LS_INSTRUCTIONS_PER_WORK`. */
    private val lsInstructionsPerWork: Double = LS_INSTRUCTIONS_PER_WORK,
    /**
     * Diversification for resumable backtrack arms: after this many consecutive
     * scheduled segments in which a resumable arm fails to improve the shared incumbent, its search
     * handle is discarded so the next schedule opens a **fresh** one. The fresh search re-descends from
     * the root under the now-tighter shared bound and re-imports the pool's learned clauses — recovering
     * the bound-guided re-exploration the pre-resume per-segment cold restart gave (which resume's single
     * persistent trail had traded away, regressing value on plateau-prone instances like `cargo`), while
     * keeping resume's fast initial convergence (it only fires after a plateau) and clause retention.
     * `0` disables re-seeding (pure resume). Only resumable (backtrack) arms are affected; LS and ALNS
     * arms already run a fresh warm-started slice (or destroy/repair loop) each segment.
     */
    private val reseedStaleThreshold: Int = 3,
    /**
     * Share of the bandit's evidence that survives the first incumbent. Finding a solution and improving one
     * are different jobs, so the scheduler starts the second with only a weak memory of who did well at the
     * first: enough that it need not re-explore every arm, little enough that a few segments overturn it. The
     * search itself carries over whole. Applies to [thompson]'s policy; another policy keeps its evidence.
     */
    private val phaseRetention: Double = DEFAULT_PHASE_RETENTION,
) : PortfolioExecutor {

    init {
        require(workers.isNotEmpty()) { "SequentialPortfolio must have at least one worker" }
        require(baseSliceMillis > 0 && maxSliceMillis >= baseSliceMillis) { "invalid slice bounds" }
        require(sliceGrowth >= 1.0) { "sliceGrowth must be ≥ 1.0" }
        require(reseedStaleThreshold >= 0) { "reseedStaleThreshold must be ≥ 0" }
        require(phaseRetention in 0.0..1.0) { "phaseRetention must be in [0, 1]" }
        require(baseSliceWork > 0 && maxSliceWork >= baseSliceWork) { "invalid work slice bounds" }
        require(lsInstructionsPerWork > 0.0) { "lsInstructionsPerWork must be > 0" }
    }

    /** The instruction allowance a counted local-search segment of [work] units receives. */
    private fun instructionsFor(work: Long): Long = (work * lsInstructionsPerWork).toLong().coerceAtLeast(1L)

    /** A per-segment cancellation that fires when the slice elapses or the global token fires. Built from
     *  [Cancellation.until] (not a bare predicate) so it carries the slice deadline — an arm can then size
     *  a sub-phase as a fraction of its slice via [Cancellation.shorten] (the ALNS bootstrap). */
    private fun sliceToken(global: Cancellation, sliceMillis: Long): Cancellation =
        Cancellation.until(TimeSource.Monotonic.markNow() + sliceMillis.milliseconds) or global

    /** The cancellation token bounding one non-resumable arm's segment. A counted-work arm
     *  ([PortfolioWorker.acceptsInstructionBudget]) runs unclocked: its own instruction budget
     *  paces it, and a wall-clock cap on top would reintroduce the machine-speed dependence counting exists to
     *  remove. Only an arm with neither a counter nor a resumable handle is sliced by [sliceMs]. */
    private fun segmentToken(worker: PortfolioWorker, cancellation: Cancellation, sliceMs: Long): Cancellation =
        if (worker.acceptsInstructionBudget) cancellation else sliceToken(cancellation, sliceMs)

    /** Geometric growth shared by every slice axis (millis and work): grow by
     *  [sliceGrowth], never past [cap]. */
    private fun grow(current: Long, cap: Long): Long = (current * sliceGrowth).toLong().coerceAtMost(cap)

    /**
     * Satisfaction: run arms in bandit-chosen segments until one returns a definitive Sat/Unsat, which ends the
     * run. Each segment settles the arm's [RewardLedger] account against the work it spent.
     *
     * A backtrack arm exposes a [ResumableSolve] ([PortfolioWorker.newResumableSolve]), held across segments and
     * resumed each time the bandit reschedules it, so the arm keeps its learned clauses and trail rather than
     * starting over every slice; it is sliced by work, so the schedule does not depend on machine speed. An arm
     * whose handle reaches a verdict that settles nothing, or fails, is retired. Local-search arms run a fresh
     * counted segment each time.
     */
    // Cleanup attempts every handle and preserves a primary failure.
    @Suppress("TooGenericExceptionCaught")
    override fun solve(cancellation: Cancellation): SolveResult {
        val handles = arrayOfNulls<ResumableSolve>(workers.size)
        val retired = BooleanArray(workers.size)
        var remaining = workers.size
        // Per-arm counters, folded as in [minimize]: a handle's are cumulative, a fresh segment's are merged.
        val perArm = arrayOfNulls<SolveStats>(workers.size)
        val verdicts = ArrayList<SolveResult>()
        val ledger = RewardLedger(workers.size)
        val progress = ProgressCredit(workers.size)
        var slice = baseSliceMillis
        var lsSliceWork = baseSliceWork
        var probed = 0
        var primaryFailure: Throwable? = null
        try {
            while (!cancellation()) {
                // Every arm first runs one base slice, in order, so each has evidence before the policy chooses.
                val probing = probed < workers.size
                val selected = if (probing) probed++ else bandit.choose()
                val arm = if (retired[selected]) {
                    bandit.update(selected, 0.0)
                    retired.indexOfFirst { !it }
                } else {
                    selected
                }
                val worker = workers[arm]
                val handle = handles[arm] ?: worker.newResumableSolve()?.also { handles[arm] = it }
                val r: SolveResult?
                var failed = false
                val work: Long
                // A failing arm leaves the others to answer, but an unsound one has answered wrongly.
                if (handle != null) {
                    val workBefore = handle.work
                    val outcome = runCatching { handle.runSlice(cancellation, Long.MAX_VALUE, baseSliceWork) }
                        .rethrowUnsound()
                    r = outcome.getOrNull()
                    failed = outcome.isFailure
                    perArm[arm] = handle.stats
                    progress.observe(ledger, arm, handle.stats)
                    work = handle.work - workBefore
                } else {
                    val token = segmentToken(worker, cancellation, slice)
                    r = runCatching { worker.solve(token, instructionsFor(lsSliceWork)) }
                        .rethrowUnsound()
                        .getOrNull()
                    r?.let { perArm[arm] = (perArm[arm] ?: SolveStats.EMPTY).mergedWith(it.stats) }
                    r?.let { progress.observe(ledger, arm, it.stats) }
                    work = lsSliceWork
                }
                creditContributions(ledger)
                bandit.update(arm, ledger.settle(arm, work), work.toDouble() / baseSliceWork)
                when (r) {
                    is SolveResult.Sat -> return r.copy(stats = foldArms(perArm))
                    is SolveResult.Unsat -> return r.copy(stats = foldArms(perArm))
                    else -> Unit
                }
                if (handle != null && (r != null || failed)) {
                    r?.let(verdicts::add)
                    retired[arm] = true
                    remaining--
                    handles[arm] = null
                    handle.close()
                    if (remaining == 0) return SolveResult.Unknown(unsettledReason(verdicts), foldArms(perArm))
                }
                // The probe runs at the base slice for every arm, so its cost stays flat in the arm count.
                if (!probing) {
                    slice = grow(slice, maxSliceMillis)
                    lsSliceWork = grow(lsSliceWork, maxSliceWork)
                }
            }
            return SolveResult.Unknown(TerminationReason.Cancelled, foldArms(perArm))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeAll(handles, primaryFailure)
        }
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
    // Cleanup attempts every handle and preserves a primary failure.
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally", "ThrowsCount")
    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        // The same verified-incumbent exchange the parallel executor folds into; here there is one writer,
        // so the strict-improvement gate is all that is being reused, not the concurrency.
        val incumbent = IncumbentExchange.minimizing<Sample>()
        val ledger = RewardLedger(workers.size)
        // A bound only the running arm can raise: a sequential pool runs one arm at a time.
        val floor = workers.firstNotNullOfOrNull { it.sharedPools?.bounds }
        val readFloor = { floor?.current() ?: Double.NEGATIVE_INFINITY }
        val progress = ProgressCredit(workers.size)
        var slice = baseSliceMillis
        var lsSliceWork = baseSliceWork
        var probed = 0
        val start = TimeSource.Monotonic.markNow()
        // The label of the arm running the current segment — single-threaded, so it is unambiguous
        // for every improvement [accept] folds while that segment is active.
        var armLabel = workers.first().label
        // The arm identity of the active segment, tracked alongside [armLabel] for attribution. The
        // sequential track never replicates, so armId == the worker's position here — nothing pools.
        var armId = workers.first().armId
        val readBound = { incumbent.bound() }
        // One resumable handle per backtrack arm, opened lazily on the arm's first segment and resumed
        // on every later one. LS arms stay null and run a fresh warm-started slice each time.
        val handles = arrayOfNulls<ResumableSearch>(workers.size)
        val retired = BooleanArray(workers.size)
        var remaining = workers.size
        // Per-arm counters. A resumable arm's handle carries them cumulatively, so its entry is replaced
        // each segment rather than accumulated; a non-resumable arm runs a fresh search per segment, so
        // its terminal verdicts are merged. Folding only terminal verdicts loses every arm the deadline
        // paused instead of finishing — which, under a wall clock, is usually all of them.
        val perArm = arrayOfNulls<SolveStats>(workers.size)
        var primaryFailure: Throwable? = null
        var callbackFailure: Throwable? = null
        // Consecutive non-improving segments per arm; drives re-seeding (see [reseedStaleThreshold]).
        val staleSegments = IntArray(workers.size)

        // Fold a strictly-improving incumbent into the shared bound + fire the telemetry callback,
        // attributing it to the arm of the active segment ([armLabel]).
        fun accept(r: MinimizeResult.WithSample) {
            if (!r.objective.isFinite() || r.objective >= readBound()) return
            try {
                onImprovement?.invoke(AttributedImprovement(armLabel, armId, start.elapsedNow(), r))
            } catch (failure: Throwable) {
                callbackFailure = failure
                throw failure
            }
            incumbent.offer(r.sample, r.objective)
        }

        try {
            while (!cancellation()) {
                // Every arm first runs one base slice, in order, so each has evidence before the policy chooses.
                val probing = probed < workers.size
                val selected = if (probing) probed++ else bandit.choose()
                val arm = if (retired[selected]) {
                    bandit.update(selected, 0.0)
                    retired.indexOfFirst { !it }
                } else {
                    selected
                }
                val hadIncumbent = incumbent.current() != null
                val before = readBound()
                val floorBefore = readFloor()
                val worker = workers[arm]
                armLabel = worker.label
                armId = worker.armId
                val handle = handles[arm] ?: worker.newResumableSearch(readBound)?.also { handles[arm] = it }
                var terminal: MinimizeResult? = null
                var failed = false
                val work: Long
                if (handle != null) {
                    val workBefore = handle.work
                    // Resume the arm's search for this slice; a terminal verdict means it finished, null
                    // means the slice elapsed (search paused, state retained for the next reschedule).
                    val outcome = runCatching {
                        handle.runSlice(cancellation, Long.MAX_VALUE, baseSliceWork) { accept(it) }
                    }
                    terminal = outcome.getOrNull()
                    failed = outcome.isFailure
                    work = handle.work - workBefore
                } else {
                    // Local-search segments restart from the shared incumbent but are bounded by their own
                    // counted work; the whole-solve deadline remains the outer cancellation terminator.
                    val armToken = segmentToken(worker, cancellation, slice)
                    runCatching {
                        for (r in worker.improvements(
                            readBound,
                            armToken,
                            warmStart = incumbent.current()?.assignment,
                            maxInstructions = instructionsFor(lsSliceWork),
                        )) {
                            terminal = r
                            if (r is MinimizeResult.WithSample) accept(r)
                        }
                    }
                    work = lsSliceWork
                }
                callbackFailure?.let { throw it }
                if (terminal is MinimizeResult.WithSample) accept(terminal)
                if (handle != null) {
                    perArm[arm] = handle.stats
                    progress.observe(ledger, arm, handle.stats)
                } else {
                    terminal?.let { perArm[arm] = (perArm[arm] ?: SolveStats.EMPTY).mergedWith(it.stats) }
                    terminal?.let { progress.observe(ledger, arm, it.stats) }
                }

                val improvement = before - readBound()
                val found = !hadIncumbent && incumbent.current() != null
                if (found) {
                    ledger.credit(arm, Signal.FirstSolution, 1.0)
                } else {
                    ledger.credit(arm, Signal.Improvement, improvement)
                }
                if (floorBefore.isFinite()) ledger.credit(arm, Signal.Floor, readFloor() - floorBefore)
                creditContributions(ledger)
                bandit.update(arm, ledger.settle(arm, work), work.toDouble() / baseSliceWork)
                if (found) startImprovementPhase(ledger)

                // Re-seed a plateaued resumable arm: after enough consecutive non-improving segments, drop
                // its handle so the next schedule re-descends from the root under the tighter bound with the
                // pool's clauses re-imported — restoring diversification without losing convergence.
                // Guards keep it from disrupting productive search: only once an incumbent exists (the
                // feasibility hunt is never reset), and never on a segment that already returned a terminal
                // verdict (a completed optimality / infeasibility proof short-circuits to the return below).
                if (handle != null && terminal == null && !failed && incumbent.current() != null) {
                    if (improvement > 0.0) {
                        staleSegments[arm] = 0
                    } else if (reseedStaleThreshold > 0 && ++staleSegments[arm] >= reseedStaleThreshold) {
                        runCatching { handle.close() }
                        handles[arm] = null
                        staleSegments[arm] = 0
                    }
                }

                // A ray proves the model unbounded whatever bound the arm ran under.
                (terminal as? MinimizeResult.Unbounded)?.let { return it.copy(stats = foldArms(perArm)) }

                // A clean segment exhaustion ends the run: any incumbent is optimal, else infeasible.
                if (PortfolioReduction.isExhausted(terminal)) {
                    return PortfolioReduction.terminal(incumbent.current(), dirty = false, foldArms(perArm))
                }
                if (handle != null && (terminal != null || failed)) {
                    retired[arm] = true
                    remaining--
                    handles[arm] = null
                    handle.close()
                    if (remaining == 0) {
                        return PortfolioReduction.terminal(incumbent.current(), dirty = true, foldArms(perArm))
                    }
                }
                // The probe runs at the base slice for every arm, so its cost stays flat in the arm count.
                if (!probing) {
                    slice = grow(slice, maxSliceMillis)
                    lsSliceWork = grow(lsSliceWork, maxSliceWork)
                }
            }
            // Cancellation stopped a still-open search: keep the incumbent (BestFound) or report Unknown.
            return PortfolioReduction.terminal(incumbent.current(), dirty = true, foldArms(perArm))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeAll(handles, primaryFailure)
        }
    }

    /** The first incumbent ends the feasibility hunt: the ledger's rates restart and the bandit keeps
     *  [phaseRetention] of its evidence. */
    private fun startImprovementPhase(ledger: RewardLedger) {
        ledger.resetPhase()
        (bandit as? DiscountedThompson)?.fade(phaseRetention)
    }

    // The pool every arm shares, when it shares one; the same object reached through any worker.
    private val contributions = workers.firstNotNullOfOrNull { it.sharedPools?.contributions }

    // Pools name an arm by its armId; a sequential pool never replicates an arm, so each id is one worker.
    private val workerOfArm = workers.indices.associateBy { workers[it].armId }

    /** Credit each arm for the uses other arms made of its shared clauses, cuts and bounds since the last call. */
    private fun creditContributions(ledger: RewardLedger) {
        contributions?.drain { kind, origin, uses ->
            workerOfArm[origin]?.let { ledger.credit(it, kind.signal, uses.toDouble()) }
        }
    }

    /**
     * Close every handle still open, attempting all of them. A close failure is suppressed into [primaryFailure]
     * when the run already failed, and thrown otherwise.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun closeAll(handles: Array<out AutoCloseable?>, primaryFailure: Throwable?) {
        var closeFailure: Throwable? = null
        for (i in handles.indices) {
            try {
                handles[i]?.close()
            } catch (failure: Throwable) {
                closeFailure?.addSuppressed(failure) ?: run { closeFailure = failure }
            }
        }
        closeFailure?.let { failure ->
            primaryFailure?.addSuppressed(failure) ?: throw failure
        }
    }

    /** Why a satisfaction run whose every resumable arm retired undecided settles nothing: an arm that declined a
     *  feature it cannot decide wins, so a caller can fall back to a backend that can. */
    private fun unsettledReason(verdicts: List<SolveResult>): TerminationReason {
        val reasons = verdicts.filterIsInstance<SolveResult.Unknown>().map { it.reason }
        return if (TerminationReason.Unsupported in reasons) {
            TerminationReason.Unsupported
        } else {
            reasons.firstOrNull() ?: TerminationReason.Cancelled
        }
    }

    /** The pool's total counters: every arm that did work, whether or not it reached a verdict. */
    private fun foldArms(perArm: Array<SolveStats?>): SolveStats =
        perArm.filterNotNull().fold(SolveStats.EMPTY) { acc, s -> acc.mergedWith(s) }

    override fun close() {
        workers.forEach { runCatching { it.close() } }
    }

    /** Policy factories. The primary constructor takes any kumulant [UnivariateBandit] reading rewards in `[0, 1]`. */
    companion object {
        /** Base slices of work after which an observation counts half as much; see [thompson]. */
        const val DEFAULT_HALF_LIFE: Double = 200.0

        /** Default share of the bandit's evidence kept across the first incumbent. */
        const val DEFAULT_PHASE_RETENTION: Double = 0.25

        /**
         * Discounted Thompson sampling, the default policy. An arm that keeps earning nothing is tried less and
         * less, with no fixed exploration share to tax the run, and evidence fades over [halfLife] base slices of
         * work so the schedule follows whichever arm is paying now.
         * Every arm first runs one base slice, in order, so the policy starts from evidence on each; at one base
         * slice apiece the probe costs little however many arms there are.
         */
        fun thompson(
            workers: List<PortfolioWorker>,
            seed: Long = 0L,
            halfLife: Double = DEFAULT_HALF_LIFE,
            baseSliceMillis: Long = 2_000,
            maxSliceMillis: Long = 60_000,
            sliceGrowth: Double = 1.5,
            reseedStaleThreshold: Int = 3,
            baseSliceWork: Long = 5_000,
            phaseRetention: Double = DEFAULT_PHASE_RETENTION,
        ): SequentialPortfolio = SequentialPortfolio(
            workers = workers,
            bandit = DiscountedThompson(workers.size, Random(seed), halfLife),
            baseSliceMillis = baseSliceMillis,
            maxSliceMillis = maxSliceMillis,
            sliceGrowth = sliceGrowth,
            reseedStaleThreshold = reseedStaleThreshold,
            baseSliceWork = baseSliceWork,
            phaseRetention = phaseRetention,
        )
    }
}

/**
 * Local-search instructions that cost as much as one backtrack search node, LP work included: the median ratio of
 * local-search moves per second to backtrack work per second, each engine alone on one core for 10s, over the 28
 * MiniZinc models where both ran (spread 0.17 to 69, geometric mean 1.9).
 */
internal const val LS_INSTRUCTIONS_PER_WORK: Double = 1.5

// An unsound arm has answered wrongly, so its failure ends the run instead of retiring the arm.
private fun <T> Result<T>.rethrowUnsound(): Result<T> = onFailure { if (it is UnsoundnessException) throw it }
