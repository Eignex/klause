@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.bound
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A bandit-scheduled portfolio of klause solver arms running on `lanes` threads. Each lane repeatedly claims an
 * arm a kumulant [UnivariateBandit] picks, runs it for one segment, and settles what the segment earned; the
 * shared incumbent, bound and pools pass between segments and lanes. One lane is the single-core track, where the
 * policy concentrates the core on whichever arm is making progress; more lanes run that same schedule
 * concurrently, never two at once on one arm. With a lane for every arm there is nothing to share, and each lane
 * runs its own arm for the whole solve.
 *
 * An **arm** is a [PortfolioWorker], built by [PortfolioBuilder], so a portfolio may mix local search, backtrack
 * and ALNS. Across segments the shared incumbent bound prunes backtrack arms (their `objectiveBoundSupplier`) and
 * the incumbent assignment warm-starts local-search arms (their `initialAssignment` seam, threaded through
 * [PortfolioWorker.improvements]'s `warmStart`).
 *
 * **Reward** (folded into the bandit in `[0, 1]`) comes from a [RewardLedger]: each arm is credited for what it
 * contributed and scored on the rate it earned that at, per unit of work, against the rest of the pool's rate.
 * Before any incumbent exists the credit is a first feasible solution, which drives the feasibility hunt; once
 * one exists it is the objective improvement, which drives anytime convergence. A rise in the pool's proven
 * lower bound is credited to the arm that proved it, so an arm closing the gap from below earns as one closing it
 * from above does. Progress short of a solution earns credit in both: variables a backtrack arm fixes at its root,
 * and a local-search arm lowering the record violation ([ProgressCredit]). The ledger starts a new phase at the
 * first incumbent, since the rates a feasibility hunt earned say nothing about who improves one. An arm is also
 * credited when another arm uses what it shared: an imported clause in a conflict or unit, an imported cut
 * selected into a relaxation, an imported bound tightening a domain ([ContributionTally]).
 *
 * **Resumable backtrack arms:** a backtrack arm exposes a [ResumableSearch] ([PortfolioWorker.newResumableSearch])
 * or, for satisfaction, a [ResumableSolve] ([PortfolioWorker.newResumableSolve]). The portfolio holds one handle
 * per such arm and *resumes* it each time the arm is scheduled, on whichever lane, so the arm continues its exact
 * search — learned clauses, trail, heuristics and LP warm-start caches intact. Local-search arms have no handle and
 * run a fresh segment warm-started from the shared incumbent. Every counted arm's segment spends the same work,
 * growing by [sliceGrowth] after each segment: a restarting arm needs the growth to dig deeper than one short
 * restart reaches, and a resumable arm takes it too, since an arm whose turns were shorter than its peers' would get
 * less of the core than the policy picks it for.
 *
 * **Re-seeding plateaued arms ([reseedStaleThreshold]):** pure resume keeps one persistent trail, which converges
 * fast but forgoes the bound-guided re-exploration a cold restart buys. A resumable arm that fails to improve the
 * incumbent for several consecutive segments has its handle discarded and rebuilt on its next schedule,
 * re-descending from the root under the tighter bound with the pool's learned clauses re-imported.
 *
 * **Quarantine ([witnessCheck]):** every model and incumbent an arm reports is checked against the model before it
 * counts, and an arm that claims infeasibility while the pool holds a verified solution is caught too. An arm
 * caught claiming a result the model refutes is retired, its claim discarded and [onFault] told, so one faulty
 * configuration cannot hand the run a wrong answer while the other arms carry on.
 */
class Portfolio(
    /** The arms; each carries its own engine, params, and objective form. */
    val workers: List<PortfolioWorker>,
    /** kumulant arm-selection policy over rewards in `[0, 1]`; see [thompson] for the default. */
    private val bandit: UnivariateBandit,
    /** Threads running segments at once; capped at the number of arms, since an arm runs on one lane at a time. */
    lanes: Int = 1,
    /** First time slice of a non-resumable arm: the only bound on one with no work counter, an outer one on a
     *  counted arm. */
    private val baseSliceMillis: Long = 2_000,
    /** Cap on a single segment's time slice. */
    private val maxSliceMillis: Long = 60_000,
    /** Geometric growth applied to a restarting arm's slice after each segment. */
    private val sliceGrowth: Double = 1.5,
    /**
     * Work the first segment of a counted arm spends, resumable or local search; later segments grow by
     * [sliceGrowth] up to [maxSliceWork].
     *
     * Work is measured in node-equivalents, one unit for every arm whatever its engine. A resumable backtrack arm
     * spends one per search node plus its LP work at the rate [com.eignex.klause.solver.ResumableSearch.runSlice]
     * charges it. A local-search or ALNS arm ([PortfolioWorker.acceptsInstructionBudget]) spends one per
     * [lsInstructionsPerWork] instructions of its counted allowance; ALNS spends it across its own outer
     * destroy/repair loop instead of one inner solve (see [com.eignex.klause.meta.alns.Alns]'s class KDoc). A
     * common unit is what lets one schedule give every arm a comparable turn.
     *
     * A counted segment ends at its work or at its time slice, whichever comes first. A segment bounded by time
     * pauses somewhere different on every run, and since the search resumes from wherever it stopped, every counter
     * a solve reports inherits that; a segment bounded by work pauses at the same point every time, which on one
     * lane makes a run reproducible. Work binds first wherever [lsInstructionsPerWork] prices an arm's steps about
     * right; the time slice is there for the models where it does not, on which one segment of a few thousand
     * units can otherwise run for seconds and hold the core past every later choice the policy would make.
     */
    private val baseSliceWork: Long = 5_000,
    /** Cap on a single counted segment's work. */
    private val maxSliceWork: Long = 150_000,
    /**
     * Wall-clock cap on a counted arm's probe segment, which every arm runs before the policy has seen any of
     * them; later counted segments are capped by their growing time slice ([baseSliceMillis]).
     */
    private val probeSliceMillis: Long = 1_000,
    /** Local-search instructions that cost as much as one search node; see `LS_INSTRUCTIONS_PER_WORK`. */
    private val lsInstructionsPerWork: Double = LS_INSTRUCTIONS_PER_WORK,
    /**
     * Consecutive non-improving segments after which a resumable arm's handle is discarded so its next schedule
     * opens a fresh one under the tighter bound; `0` disables re-seeding. Local-search and ALNS arms already run
     * a fresh warm-started segment each time.
     */
    private val reseedStaleThreshold: Int = 3,
    /**
     * Share of the bandit's evidence that survives the first incumbent. Finding a solution and improving one are
     * different jobs, so the scheduler starts the second with only a weak memory of who did well at the first:
     * enough that it need not re-explore every arm, little enough that a few segments overturn it. The search
     * itself carries over whole. Applies to [thompson]'s policy; another policy keeps its evidence.
     */
    private val phaseRetention: Double = DEFAULT_PHASE_RETENTION,
    /** Checks every result an arm claims before it is accepted; null trusts the arms. See [WitnessCheck]. */
    private val witnessCheck: WitnessCheck? = null,
    /** Told about each arm the run quarantines for a refuted claim; see [ArmFault]. */
    private val onFault: ((ArmFault) -> Unit)? = null,
    /**
     * Least share of the run's segment time each arm is owed, by arm; empty owes none. An arm below its share is
     * scheduled before the policy chooses, so an arm whose progress earns no credit until it proves something — a
     * complete search closing in on a bound — is not starved by arms that improve the incumbent often.
     */
    private val minShares: DoubleArray = DoubleArray(0),
) : PortfolioExecutor {
    private val lanes = minOf(lanes, workers.size)

    // The policy shares the run's time between families, not arms; see [remainingShare].
    private val familyCount = workers.distinctBy { it.family }.size

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
        require(lanes >= 1) { "lanes must be ≥ 1" }
        require(baseSliceMillis > 0 && maxSliceMillis >= baseSliceMillis) { "invalid slice bounds" }
        require(sliceGrowth >= 1.0) { "sliceGrowth must be ≥ 1.0" }
        require(reseedStaleThreshold >= 0) { "reseedStaleThreshold must be ≥ 0" }
        require(phaseRetention in 0.0..1.0) { "phaseRetention must be in [0, 1]" }
        require(baseSliceWork > 0 && maxSliceWork >= baseSliceWork) { "invalid work slice bounds" }
        require(probeSliceMillis > 0) { "probeSliceMillis must be > 0" }
        require(lsInstructionsPerWork > 0.0) { "lsInstructionsPerWork must be > 0" }
        require(minShares.isEmpty() || minShares.size == workers.size) { "minShares must give every arm a share" }
        require(minShares.all { it >= 0.0 } && minShares.sum() <= 1.0) { "minShares must be shares of one run" }
    }

    // The pool every arm shares, when it shares one; the same object reached through any worker.
    private val contributions = workers.firstNotNullOfOrNull { it.sharedPools?.contributions }

    /**
     * Satisfaction: run arms in scheduled segments until one returns a definitive Sat/Unsat, which ends the run.
     * A backtrack arm's [ResumableSolve] is resumed each time it is scheduled; an arm whose handle reaches a verdict
     * that settles nothing, or fails, is retired. Local-search arms run a fresh counted segment each time.
     */
    override fun solve(cancellation: Cancellation): SolveResult {
        val run = Schedule(cancellation, arrayOfNulls<ResumableSolve>(workers.size))
        val verdicts = ArrayList<SolveResult>()
        var decided: SolveResult? = null
        run.execute { claim ->
            val arm = claim.arm
            val worker = workers[arm]
            val setup = TimeSource.Monotonic.markNow()
            val handle = run.handles[arm] ?: worker.newResumableSolve()?.also {
                run.handles[arm] = it
                run.log.initialized(arm, setup.elapsedNow().inWholeMilliseconds)
            }
            val r: SolveResult?
            val failure: Throwable?
            val work: Long
            if (handle != null) {
                val workBefore = handle.work
                val outcome = runCatching {
                    handle.runSlice(run.token, handleMillis(run.token, claim), claim.handleNodes)
                }
                r = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
                work = handle.work - workBefore
            } else {
                val token = segmentToken(worker, run.token, claim)
                val outcome = runCatching { worker.solve(token, instructionsOf(claim)) }
                r = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
                work = countedWork(claim, r?.stats)
            }
            val failed = failure != null
            // A failing arm leaves the others to answer; an unsound one answered wrongly, so it is quarantined too.
            if (failure is UnsoundnessException) claim.fault = failure.message
            if (r is SolveResult.Sat) {
                witnessCheck?.refute(r.assignment, null)?.let {
                    claim.fault = "claimed a model the problem refutes: $it"
                }
            }
            run.locked {
                run.record(claim, handle?.stats ?: r?.stats, cumulative = handle != null, work = work, failed = failed)
                if (claim.fault != null) {
                    run.quarantine(claim)
                    return@locked
                }
                if ((r is SolveResult.Sat || r is SolveResult.Unsat) && decided == null) {
                    decided = r
                    run.finish()
                }
                // An arm that threw is retired like one that finished: rescheduling it would only fail again.
                if (failed || (handle != null && r != null)) {
                    r?.let(verdicts::add)
                    run.retire(arm)
                }
            }
        }
        val stats = run.folded()
        return when (val r = decided) {
            is SolveResult.Sat -> r.copy(stats = stats)

            is SolveResult.Unsat -> r.copy(stats = stats)

            else -> if (run.allRetired) {
                SolveResult.Unknown(unsettledReason(verdicts), stats)
            } else {
                SolveResult.Unknown(TerminationReason.Cancelled, stats)
            }
        }
    }

    /**
     * Branch-and-bound: run arms in scheduled segments, carrying one shared incumbent. Each segment streams
     * against its arm's own objective representation, sees the shared bound (backtrack prunes on it) and the
     * incumbent assignment (local search warm-starts from it). Returns [MinimizeResult.Optimal] /
     * [MinimizeResult.Infeasible] only when an arm exhausts its search, otherwise the best incumbent as
     * [MinimizeResult.BestFound]. `onImprovement` fires once per strict global improvement, tagged with the arm
     * that produced it, in the order the incumbent installed them.
     */
    // A callback failure stays the primary failure.
    @Suppress("TooGenericExceptionCaught")
    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        val run = Schedule(cancellation, arrayOfNulls<ResumableSearch>(workers.size))
        val incumbent = IncumbentExchange.minimizing<Sample>()
        val start = TimeSource.Monotonic.markNow()
        val readBound = { incumbent.bound() }
        // Consecutive non-improving segments per arm; drives re-seeding (see [reseedStaleThreshold]).
        val staleSegments = IntArray(workers.size)
        var callbackFailure: Throwable? = null
        var unbounded: MinimizeResult.Unbounded? = null
        var exhausted = false

        // Install a strictly-improving incumbent and credit it to [claim]'s arm. Called under the run's lock, so the
        // check, the callback and the install are one step: concurrent lanes report improvements in the order they
        // installed and never report one a peer already beat.
        fun install(claim: Claim, r: MinimizeResult.WithSample) {
            val before = readBound()
            if (r.objective >= before) return
            val worker = workers[claim.arm]
            try {
                onImprovement?.invoke(AttributedImprovement(worker.label, worker.armId, start.elapsedNow(), r))
            } catch (failure: Throwable) {
                callbackFailure = failure
                run.finish()
                throw failure
            }
            if (incumbent.offer(r.sample, r.objective) !is Publication.Installed) return
            claim.improved = true
            if (before.isFinite()) {
                if (claim.hadIncumbent) run.ledger.credit(claim.arm, Signal.Improvement, before - r.objective)
            } else if (!claim.foundFirst) {
                claim.foundFirst = true
                run.ledger.credit(claim.arm, Signal.FirstSolution, 1.0)
            }
        }

        // Check [claim]'s waiting candidate: refuted, it marks the arm faulty; verified, it goes to [install]. Checked
        // outside the lock: re-deriving a whole assignment is the costly part, and peers need not wait.
        fun check(claim: Claim) {
            val r = claim.pending ?: return
            claim.pending = null
            if (claim.fault != null || r.objective >= readBound()) return
            val started = TimeSource.Monotonic.markNow()
            witnessCheck?.refute(r.sample, r.objective)?.let {
                claim.fault = "claimed an incumbent the problem refutes: $it"
                return
            }
            claim.checked(started.elapsedNow())
            run.locked { install(claim, r) }
        }

        // Offer [claim]'s arm's candidate. Re-deriving a whole assignment can cost far more than the search took to
        // find it, and on a model whose incumbents come a millisecond apart a check on each one leaves the arm
        // verifying instead of searching. So the best candidate waits, and is checked once checks have taken no more
        // than [CHECK_SHARE] of the arm's time; whatever still waits is checked as the segment ends.
        fun accept(claim: Claim, r: MinimizeResult.WithSample) {
            if (claim.fault != null || !r.objective.isFinite() || r.objective >= readBound()) return
            val waiting = claim.pending
            if (waiting == null || r.objective < waiting.objective) claim.pending = r
            if (claim.checkDue()) check(claim)
        }

        run.execute { claim ->
            val arm = claim.arm
            val worker = workers[arm]
            claim.hadIncumbent = incumbent.current() != null
            val setup = TimeSource.Monotonic.markNow()
            val handle = run.handles[arm] ?: worker.newResumableSearch(readBound)?.also {
                run.handles[arm] = it
                run.log.initialized(arm, setup.elapsedNow().inWholeMilliseconds)
            }
            var terminal: MinimizeResult? = null
            val failure: Throwable?
            val work: Long
            if (handle != null) {
                val workBefore = handle.work
                // A terminal verdict means the arm finished; null means the slice ended with the search paused.
                val outcome = runCatching {
                    handle.runSlice(run.token, handleMillis(run.token, claim), claim.handleNodes) { accept(claim, it) }
                }
                terminal = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
                work = handle.work - workBefore
            } else {
                // Local-search segments restart from the shared incumbent, bounded by their own counted work.
                val armToken = segmentToken(worker, run.token, claim)
                failure = runCatching {
                    for (r in worker.improvements(
                        readBound,
                        armToken,
                        warmStart = incumbent.current()?.assignment,
                        maxInstructions = instructionsOf(claim),
                    )) {
                        terminal = r
                        if (r is MinimizeResult.WithSample) accept(claim, r)
                    }
                }.exceptionOrNull()
                work = countedWork(claim, terminal?.stats)
            }
            callbackFailure?.let { throw it }
            val failed = failure != null
            if (failure is UnsoundnessException) claim.fault = failure.message
            (terminal as? MinimizeResult.WithSample)?.let { accept(claim, it) }
            check(claim)
            if (terminal is MinimizeResult.Infeasible && incumbent.current() != null) {
                claim.fault = "claimed infeasibility while the pool holds a verified solution"
            }
            run.locked {
                val stats = handle?.stats ?: terminal?.stats
                run.record(claim, stats, cumulative = handle != null, work = work, failed = failed)
                if (claim.fault != null) {
                    run.quarantine(claim)
                    return@locked
                }
                if (claim.foundFirst) run.startImprovementPhase()
                // Re-seed a plateaued resumable arm: only once an incumbent exists (the feasibility hunt is never
                // reset), and never on a segment that already returned a terminal verdict.
                if (handle != null && terminal == null && !failed && incumbent.current() != null) {
                    if (claim.improved) {
                        staleSegments[arm] = 0
                    } else if (reseedStaleThreshold > 0 && ++staleSegments[arm] >= reseedStaleThreshold) {
                        runCatching { handle.close() }
                        run.handles[arm] = null
                        staleSegments[arm] = 0
                    }
                }
                // A ray proves the model unbounded whatever bound the arm ran under.
                (terminal as? MinimizeResult.Unbounded)?.let {
                    if (unbounded == null) unbounded = it
                    run.finish()
                }
                // A clean segment exhaustion ends the run: any incumbent is optimal, else infeasible.
                if (PortfolioReduction.isExhausted(terminal)) {
                    exhausted = true
                    run.finish()
                }
                // An arm that threw is retired like one that finished: rescheduling it would only fail again.
                if (failed || (handle != null && terminal != null)) run.retire(arm)
            }
        }
        val stats = run.folded()
        unbounded?.let { return it.copy(stats = stats) }
        // Cancellation or retirement stopped a still-open search: keep the incumbent (BestFound) or report Unknown.
        return PortfolioReduction.terminal(incumbent.current(), dirty = !exhausted, stats)
    }

    /** One segment's assignment: the arm a lane claimed and what it may spend, plus what its segment found. A
     *  [whole] segment is the arm's share of the entire solve, run on a lane of its own until the run ends. */
    private class Claim(
        val arm: Int,
        val probing: Boolean,
        val sliceMillis: Long,
        val sliceWork: Long,
        val whole: Boolean = false,
    ) {
        /** The work a resumable handle's slice may spend; a negative allowance leaves it to the run's token. */
        val handleNodes: Long get() = if (whole) -1L else sliceWork

        /** When the lane claimed the segment. */
        val started = TimeSource.Monotonic.markNow()

        var hadIncumbent = false
        var improved = false
        var foundFirst = false

        // Why the segment's claim was refuted, when it was; the arm is quarantined as the segment settles.
        var fault: String? = null

        // The best incumbent the segment found that is not checked yet, and when the next check is due.
        var pending: MinimizeResult.WithSample? = null
        private var nextCheck = TimeSource.Monotonic.markNow()

        fun checkDue(): Boolean = nextCheck.hasPassedNow()

        /** A check that took [cost] defers the next until checking has used no more than [CHECK_SHARE] of the time. */
        fun checked(cost: Duration) {
            nextCheck = TimeSource.Monotonic.markNow() + cost * ((1.0 - CHECK_SHARE) / CHECK_SHARE)
        }
    }

    /**
     * The state one `solve` or `minimize` call shares across its lanes: the ledger, the arms' [handles] and stats,
     * which arms are busy or retired, and the slice sizes. Everything but the handles is touched under [locked]; a
     * handle is touched only by the lane holding its arm.
     */
    @Suppress("TooGenericExceptionCaught")
    private inner class Schedule<H : AutoCloseable>(cancellation: Cancellation, val handles: Array<H?>) {
        private val lock = (if (lanes > 1) Concurrency.Strict else Concurrency.None).lock()
        private val stopped = AtomicBoolean(false)

        /** The run's token: the caller's, stopped early once any lane settles the run. */
        val token: Cancellation = cancellation.alsoStoppedBy(stopped)
        val ledger = RewardLedger(workers.size) { arm, signal -> signal.earnableBy(workers[arm]) }
        private val progress = ProgressCredit(workers.size)
        val log = ScheduleLog(workers)

        // A handle's counters are cumulative, so its entry is replaced; a fresh segment's are merged.
        private val perArm = arrayOfNulls<SolveStats>(workers.size)
        private val busy = BooleanArray(workers.size)
        private val retired = BooleanArray(workers.size)
        private var remaining = workers.size
        private var probed = 0

        // Segment time each arm has run, and all arms together, for the shares [minShares] owes.
        private val armNanos = LongArray(workers.size)
        private var totalNanos = 0L
        private var slice = baseSliceMillis
        private var sliceWork = baseSliceWork
        private val families = FamilyPolicy(bandit.random)
        private var improving = false

        /** Whether every arm has retired. */
        val allRetired: Boolean get() = remaining == 0

        fun <T> locked(action: () -> T): T = lock.withLock { action() }

        /** End the run: every lane stops at its next poll and claims nothing more. */
        fun finish() = stopped.store(true)

        /**
         * Run [segment] on the lanes until the run is finished, cancelled, or out of arms, then close every handle
         * still open and rethrow the first failure a lane hit, a close failure suppressed into it.
         */
        fun execute(segment: (Claim) -> Unit) {
            parallelRun(List(lanes) { index -> { lane(index, segment) } })
            closeAll(laneFailure)
            laneFailure?.let { throw it }
        }

        // The first failure any lane hit; it ends the run and is rethrown once every lane has joined.
        private var laneFailure: Throwable? = null

        // Runs segments until the run ends. Never throws: a native lane cannot hand an exception back, so a failure
        // is recorded for [execute] to rethrow.
        private fun lane(index: Int, segment: (Claim) -> Unit) {
            try {
                while (!token()) {
                    val claim = claim(index) ?: break
                    val started = TimeSource.Monotonic.markNow()
                    try {
                        segment(claim)
                    } finally {
                        val spent = started.elapsedNow().inWholeNanoseconds
                        locked {
                            busy[claim.arm] = false
                            armNanos[claim.arm] += spent
                            totalNanos += spent
                        }
                    }
                }
            } catch (failure: Throwable) {
                locked { if (laneFailure == null) laneFailure = failure }
                finish()
            }
        }

        /**
         * The next arm for lane [lane], or null when none is free. With a lane for every arm, each lane keeps its own
         * arm: there is nothing to share, so neither a probe nor the policy has a choice to make. Otherwise every arm
         * first runs one base slice, in order, so the policy starts from evidence on each; then the policy picks
         * among arms neither busy nor retired.
         */
        private fun claim(lane: Int): Claim? = locked {
            if (remaining == 0) return@locked null
            val dedicated = lanes == workers.size
            val probing = !dedicated && probed < workers.size
            val arm = when {
                dedicated -> lane
                probing -> probed++
                else -> policyPick()
            }
            if (arm < 0 || retired[arm] || busy[arm]) return@locked null
            busy[arm] = true
            Claim(arm, probing, slice, sliceWork, whole = dedicated)
        }

        // The free arm furthest below its owed share, else the policy's pick among free arms: a family first, so a
        // family's share does not grow with its arm count, then an arm of that family. LNS works on an incumbent, so it
        // waits for one unless it is all that is left.
        private fun policyPick(): Int {
            owedArm()?.let { return it }
            val free = workers.indices.filter { !busy[it] && !retired[it] }
            if (free.isEmpty()) return -1
            val present = free.mapTo(LinkedHashSet()) { workers[it].family }
            val eligible = if (improving) present else present.filter { it != ArmFamily.Lns }.ifEmpty { present }
            val family = families.choose(eligible)
            return armAmong(free.filter { workers[it].family == family })
        }

        // A policy that cannot be restricted to [candidates] is asked until it names one of them.
        private fun armAmong(candidates: List<Int>): Int {
            (bandit as? DiscountedThompson)?.let { return it.chooseAmong(candidates) }
            repeat(workers.size) {
                val chosen = bandit.choose()
                if (chosen in candidates) return chosen
            }
            return candidates.first()
        }

        /** The first incumbent ends the feasibility hunt: the ledger's rates restart, the bandit keeps
         *  [phaseRetention] of its evidence, and LNS arms become eligible. Call under [locked]. */
        fun startImprovementPhase() {
            improving = true
            ledger.resetPhase()
            (bandit as? DiscountedThompson)?.fade(phaseRetention)
        }

        private fun owedArm(): Int? {
            if (minShares.isEmpty()) return null
            var owed: Int? = null
            var deficit = 0.0
            for (arm in workers.indices) {
                if (busy[arm] || retired[arm]) continue
                val short = minShares[arm] * totalNanos - armNanos[arm]
                if (short > deficit) {
                    owed = arm
                    deficit = short
                }
            }
            return owed
        }

        /**
         * Settle [claim]'s segment: fold its [stats], credit its progress and every contribution used since the
         * last settle, and score the arm. A segment weighs the share of its slice it spent, so one cut short by a
         * verdict counts as less evidence; every arm runs the same slice, so a full segment of any arm weighs one. A
         * segment that [failed] earns nothing and weighs a full one. Call under [locked].
         */
        fun record(claim: Claim, stats: SolveStats?, cumulative: Boolean, work: Long, failed: Boolean) {
            val arm = claim.arm
            if (stats != null) {
                perArm[arm] = if (cumulative) stats else (perArm[arm] ?: SolveStats.EMPTY).mergedWith(stats)
                progress.observe(ledger, arm, stats)
            }
            contributions?.drain { kind, origin, amount ->
                if (origin in workers.indices) ledger.credit(origin, kind.signal, amount)
            }
            val earned = ledger.settle(arm, work)
            val reward = if (failed) 0.0 else earned
            val weight = (if (failed) maxOf(work, claim.sliceWork) else work).toDouble() / claim.sliceWork
            bandit.update(arm, reward, weight)
            families.record(workers[arm].family, progressed = reward > 0.0, plateau = improving)
            // Credit an arm earns while others run, from peers using what it shared, pays out now as one segment's
            // evidence: an arm the policy has stopped picking would otherwise hold it forever.
            for (other in workers.indices) {
                if (other == arm || busy[other] || retired[other] || !ledger.hasPending(other)) continue
                bandit.update(other, ledger.settleIdle(other, claim.sliceWork), 1.0)
            }
            log.record(arm, work, claim.started.elapsedNow().inWholeMilliseconds, reward, failed)
            // The probe runs at the base slice for every arm, so its cost stays flat in the arm count.
            if (!claim.probing) {
                slice = grow(slice, maxSliceMillis)
                sliceWork = grow(sliceWork, maxSliceWork)
            }
        }

        /** Quarantine [claim]'s arm for its refuted claim: retire it, count the fault, and report it. Call under
         *  [locked]. */
        fun quarantine(claim: Claim) {
            val worker = workers[claim.arm]
            log.fault(claim.arm)
            retire(claim.arm)
            onFault?.invoke(ArmFault(worker.label, worker.armId, checkNotNull(claim.fault)))
        }

        /** Retire [arm]: close its handle and never schedule it again; the run ends once none remain. Call under
         *  [locked]. */
        fun retire(arm: Int) {
            if (retired[arm]) return
            retired[arm] = true
            remaining--
            handles[arm]?.close()
            handles[arm] = null
            if (remaining == 0) finish()
        }

        /** The pool's total counters, every arm that did work included, with the schedule attached. */
        fun folded(): SolveStats = perArm.filterNotNull().fold(SolveStats.EMPTY) { acc, s -> acc.mergedWith(s) }
            .copy(portfolio = log.stats(ledger))

        private fun closeAll(primaryFailure: Throwable?) {
            var closeFailure: Throwable? = null
            for (i in handles.indices) {
                try {
                    handles[i]?.close()
                } catch (failure: Throwable) {
                    closeFailure?.addSuppressed(failure) ?: run { closeFailure = failure }
                }
                handles[i] = null
            }
            closeFailure?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }
    }

    /** The instruction allowance a counted local-search segment of [work] units receives. */
    private fun instructionsFor(work: Long): Long = (work * lsInstructionsPerWork).toLong().coerceAtLeast(1L)

    // A whole segment runs until the run ends, so it has no allowance of its own.
    private fun instructionsOf(claim: Claim): Long =
        if (claim.whole) Long.MAX_VALUE else instructionsFor(claim.sliceWork)

    // A counted segment spends its allowance; a whole one spent what its moves add up to.
    private fun countedWork(claim: Claim, stats: SolveStats?): Long = if (claim.whole) {
        ((stats?.ls?.moves?.sum ?: 0.0) / lsInstructionsPerWork).toLong()
    } else {
        claim.sliceWork
    }

    /**
     * The cancellation token bounding one non-resumable arm's segment: its time slice, or [probeSliceMillis] for a
     * counted arm's probe, and never more than its [remainingShare] of the time the run has left. The share keeps a
     * short budget from going to a few long segments: with the probes and a couple of grown slices spending it, the
     * policy would get almost no choices to make. A counted arm ([PortfolioWorker.acceptsInstructionBudget]) usually
     * ends at its instruction budget first; see [baseSliceWork]. An arm with neither a counter nor a resumable handle
     * has only the time bounds, a deadline it can size a sub-phase against ([Cancellation.shorten]). A whole segment
     * has none.
     */
    private fun segmentToken(worker: PortfolioWorker, cancellation: Cancellation, claim: Claim): Cancellation {
        if (claim.whole) return cancellation
        val slice = if (claim.probing && worker.acceptsInstructionBudget) probeSliceMillis else claim.sliceMillis
        return until(slice) or cancellation.shorten(remainingShare(claim))
    }

    /** The time a resumable arm's slice may run before its work runs out: its time slice, within its
     *  [remainingShare] of what the run has left, as a counted segment's ([segmentToken]). A node is priced at one
     *  unit whatever it costs, so without the bound a slice of a few thousand expensive nodes holds the core for the
     *  run. */
    private fun handleMillis(cancellation: Cancellation, claim: Claim): Long {
        if (claim.whole) return Long.MAX_VALUE
        val deadline = cancellation.deadline() ?: return claim.sliceMillis
        val share = (deadline - TimeSource.Monotonic.markNow()) * remainingShare(claim)
        return minOf(claim.sliceMillis, share.inWholeMilliseconds.coerceAtLeast(1L))
    }

    /**
     * The share of the time the run has left one segment may take: half of it split between the families the
     * policy chooses among, since the policy shares time by family and an arm's segments add up to its family's.
     * A segment cut to a share per arm would chop a proof into pieces too short to finish however much of the run
     * its family won. Probes run before the policy chooses anything, one for every arm, so each takes a share per
     * arm, and the probes together never take more than half the run.
     */
    private fun remainingShare(claim: Claim): Double =
        REMAINING_SHARE / (if (claim.probing) workers.size else familyCount)

    private fun until(millis: Long): Cancellation =
        Cancellation.until(TimeSource.Monotonic.markNow() + millis.milliseconds)

    /** Geometric growth shared by every slice axis (millis and work): grow by [sliceGrowth], never past [cap]. */
    private fun grow(current: Long, cap: Long): Long = (current * sliceGrowth).toLong().coerceAtMost(cap)

    /** Why a satisfaction run whose every arm retired undecided settles nothing: an arm that declined a feature it
     *  cannot decide wins, so a caller can fall back to a backend that can. */
    private fun unsettledReason(verdicts: List<SolveResult>): TerminationReason {
        val reasons = verdicts.filterIsInstance<SolveResult.Unknown>().map { it.reason }
        return if (TerminationReason.Unsupported in reasons) {
            TerminationReason.Unsupported
        } else {
            reasons.firstOrNull() ?: TerminationReason.Cancelled
        }
    }

    override fun close() {
        workers.forEach { runCatching { it.close() } }
    }

    /** Policy factories. The primary constructor takes any kumulant [UnivariateBandit] reading rewards in `[0, 1]`. */
    companion object {
        /** Full segments after which an observation counts half as much; see [thompson]. */
        const val DEFAULT_HALF_LIFE: Double = 200.0

        /** Default share of the bandit's evidence kept across the first incumbent. */
        const val DEFAULT_PHASE_RETENTION: Double = 0.25

        /**
         * Discounted Thompson sampling, the default policy, on [lanes] threads. An arm that keeps earning nothing
         * is tried less and less, with no fixed exploration share to tax the run, and evidence fades over
         * [halfLife] full segments so the schedule follows whichever arm is paying now. Every arm first runs
         * one base slice, in order, so the policy starts from evidence on each; at one base slice apiece the probe
         * costs little however many arms there are.
         */
        fun thompson(
            workers: List<PortfolioWorker>,
            lanes: Int = 1,
            seed: Long = 0L,
            halfLife: Double = DEFAULT_HALF_LIFE,
            baseSliceMillis: Long = 2_000,
            maxSliceMillis: Long = 60_000,
            sliceGrowth: Double = 1.5,
            reseedStaleThreshold: Int = 3,
            baseSliceWork: Long = 5_000,
            probeSliceMillis: Long = 1_000,
            phaseRetention: Double = DEFAULT_PHASE_RETENTION,
            witnessCheck: WitnessCheck? = null,
            onFault: ((ArmFault) -> Unit)? = null,
            minShares: DoubleArray = DoubleArray(0),
        ): Portfolio = Portfolio(
            workers = workers,
            bandit = DiscountedThompson(workers.size, Random(seed), halfLife),
            lanes = lanes,
            baseSliceMillis = baseSliceMillis,
            maxSliceMillis = maxSliceMillis,
            sliceGrowth = sliceGrowth,
            reseedStaleThreshold = reseedStaleThreshold,
            baseSliceWork = baseSliceWork,
            probeSliceMillis = probeSliceMillis,
            phaseRetention = phaseRetention,
            witnessCheck = witnessCheck,
            onFault = onFault,
            minShares = minShares,
        )
    }
}

// The share of the time a run has left that its arms' next segments may take together; see `Portfolio.segmentToken`.
private const val REMAINING_SHARE = 0.5

// Most of an arm's time incumbent checks may take; see `Portfolio.minimize`.
private const val CHECK_SHARE = 0.2

// The caller's token that also stops on [flag], keeping the caller's deadline.
private fun Cancellation.alsoStoppedBy(flag: AtomicBoolean): Cancellation =
    cancelledWhen(this::deadline) { flag.load() || this() }
