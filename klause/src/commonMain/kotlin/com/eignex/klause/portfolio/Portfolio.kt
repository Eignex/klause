@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SchedulingCancellation
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal fun initialProbeFraction(arm: Int, eligible: List<Int>, weights: IntArray): Double {
    val weight = weights.getOrElse(arm) { 1 }.toLong()
    val total = eligible.sumOf { weights.getOrElse(it) { 1 }.toLong() }.coerceAtLeast(weight)
    return weight.toDouble() / total
}

/**
 * Bandit-scheduled portfolio of [PortfolioWorker] arms built by [PortfolioBuilder], mixing local
 * search, backtrack and ALNS. Each lane claims an arm selected by [UnivariateBandit], runs one
 * segment and settles its reward. An arm runs on at most one lane at a time. Until a witness is
 * accepted, scheduled lanes owe complete-search arms their share of elapsed time. With an incumbent, retained
 * non-restarting proof workers share half the time since its last improvement; other turns favor progress.
 * With a lane per arm, each arm runs for the whole solve.
 *
 * The shared incumbent cutoff prunes backtrack arms and its assignment warm-starts local-search
 * arms. [RewardLedger] scores contributions per unit of work against the pool's rate: first
 * feasibility, objective improvement, proven lower-bound increases, root deductions and residual
 * violation improvements ([ProgressCredit]). It begins a new reward phase at first feasibility.
 * [ContributionTally] credits peers' use of imported clauses, cuts and bounds.
 *
 * [ResumableSearch] and [ResumableSolve] handles retain traversal, learned clauses, heuristics
 * and warm-start state across segments. Local-search satisfaction handles retain assignment,
 * RNG and restart state. Optimizing arms without handles restart from the shared incumbent.
 * Counted segment allowances grow by [sliceGrowth] equally across arms so restarting searches
 * can explore deeper while scheduled work shares remain comparable.
 *
 * [reseedStaleThreshold] rebuilds plateaued observable or restart-configured workers from the
 * shared incumbent. Complete search without restarts retains its traversal and imports tighter
 * cutoffs, since a segment without improvement can advance an optimality proof.
 *
 * [witnessCheck] validates reported models and incumbents and rejects infeasibility claims when
 * a verified solution exists. A faulty arm is retired, its claim discarded and [onFault] notified.
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
     * a solve reports inherits that. A work-bound segment pauses at the same point for the same incoming state;
     * elapsed coverage can still change later arm choices on one lane. Work binds first wherever
     * [lsInstructionsPerWork] prices an arm's steps about right; the time slice is there for models where it does
     * not, on which one segment of a few thousand units can otherwise run for seconds and hold the core past every
     * later choice the policy would make.
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
     * Non-improving segments before an observable arm reseeds; scheduled restarting backtrack arms count
     * equivalent base work segments. Whole lanes count each positive-work return. `0` disables re-seeding.
     */
    private val reseedStaleThreshold: Int = 3,
    /**
     * Share of the bandit's evidence that survives the first incumbent. Finding a solution and improving one are
     * different jobs, so the scheduler starts the second with only a weak memory of who did well at the first:
     * enough that it need not re-explore every arm, little enough that a few segments overturn it. The search
     * itself carries over whole. Applies to [thompson]'s policy; another policy keeps its evidence.
     */
    private val phaseRetention: Double = DEFAULT_PHASE_RETENTION,
    /** The model's classification, which sets the lean each engine family starts from; null leans none. */
    private val profile: ProblemProfile? = null,
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
    /** Charged-work scheduling and streaming diagnostics; null preserves elapsed-time scheduling. */
    private val diagnostics: PortfolioDiagnostics? = null,
    /** Opt-in reward experiment, independent of coverage and admission. Null retains ordinary rewards. */
    private val rewardPolicy: PortfolioRewardPolicy? = null,
    /** Physical progress stream; it grants no deterministic scheduling or proof authority. */
    private val onProgress: ((PortfolioEvent) -> Unit)? = null,
) : PortfolioExecutor {
    internal var runTimeSource: TimeSource = TimeSource.Monotonic
    internal var invocationStarted: TimeMark? = null
    internal var reserveBeforeIncumbentOnly: Boolean = false
    internal var disjointBooleanProof: Boolean = false
    internal var disjointBooleanProbe: Boolean = false
    internal var lastBooleanProofProbeWork: Long = 0L
        private set
    internal var lastBooleanProofProbeSegments: Int = 0
        private set
    internal var lastBooleanProofPartitionLiteral: Int? = null
        private set
    internal var lastBooleanProofTasks: Int = 0
        private set
    internal var lastBooleanProofCompleted: Int = 0
        private set
    internal var lastBooleanProofWork: Long = 0L
        private set
    internal var lastBooleanProofDecline: String? = null
        private set
    internal var incumbentProbeArms: Set<Int> = emptySet()
    internal var initialProbeWeights: IntArray = intArrayOf()
        set(value) {
            require(value.isEmpty() || value.size == workers.size)
            require(value.all { it > 0 })
            field = value.copyOf()
        }
    internal var improvementProofShare: Double = 0.5
        set(value) {
            require(value.isFinite() && value in 0.5..1.0)
            field = value
        }
    internal var includeRestartingProof: Boolean = false

    internal var evidenceVerification: PortfolioEvidence? = workers.firstNotNullOfOrNull { it.evidenceModel }?.let {
        val verifier = PortfolioEvidence(it, CandidateVerifier.trusting())
        val binding = workers.firstNotNullOfOrNull { worker -> worker.sharingInvocation }
        if (binding != null && workers.all { worker -> worker.sharingInvocation?.matches(binding) == true }) {
            verifier.bindInvocation(binding)
        }
        verifier
    }

    private val lanes = minOf(lanes, workers.size)

    // The policy shares the run's time between families, not arms; see [remainingShare].
    private val familyCount = workers.distinctBy { it.family }.size

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
        require(lanes >= 1) { "lanes must be ≥ 1" }
        require(diagnostics == null || lanes == 1) { "diagnostic scheduling requires one lane" }
        require(baseSliceMillis > 0 && maxSliceMillis >= baseSliceMillis) { "invalid slice bounds" }
        require(sliceGrowth.isFinite() && sliceGrowth >= 1.0) { "sliceGrowth must be finite and ≥ 1.0" }
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
     * that settles nothing, or fails, is retired. Local-search satisfaction walks resume across counted segments.
     */
    override fun solve(cancellation: Cancellation): SolveResult = qualify(solveRun(cancellation))

    private fun sourceSemantics(): EffectiveSourceSemantics? {
        val model = workers.firstOrNull()?.evidenceModel ?: return null
        return model.semantics.takeIf {
            workers.all { worker -> worker.evidenceModel?.sameModel(model) == true }
        }
    }

    private fun qualify(result: SolveResult): SolveResult =
        sourceSemantics()?.let { result.withSourceSemantics(it) } ?: result

    private fun qualify(result: MinimizeResult): MinimizeResult =
        sourceSemantics()?.let { result.withSourceSemantics(it) } ?: result

    private fun qualify(result: MinimizeResult.WithSample): MinimizeResult.WithSample =
        sourceSemantics()?.let { result.withSourceSemantics(it) } ?: result

    private fun solveRun(cancellation: Cancellation): SolveResult {
        val run = Schedule(cancellation, arrayOfNulls<ResumableSolve>(workers.size))
        lastBooleanProofTasks = 0
        lastBooleanProofCompleted = 0
        lastBooleanProofWork = 0L
        lastBooleanProofProbeWork = 0L
        lastBooleanProofProbeSegments = 0
        lastBooleanProofPartitionLiteral = null
        lastBooleanProofDecline = if (disjointBooleanProof) "unsupported-schedule" else "disabled"
        var proofTasks: BooleanProofPortfolio? = null
        if (disjointBooleanProof && lanes == 1 && baseSliceWork == 5000L &&
            maxSliceWork == 150000L && sliceGrowth == 1.5) {
            lastBooleanProofDecline = null
            proofTasks = BooleanProofPortfolio.forWorkers(workers, disjointBooleanProbe) {
                lastBooleanProofDecline = it
            }
            proofTasks?.let { run.restrictAvailability(it::available) }
        }
        val verdicts = ArrayList<SolveResult>()
        var decided: SolveResult? = null
        val execution = runCatching {
            run.execute { claim ->
                val arm = claim.arm
                val worker = workers[arm]
                val opening = run.handles[arm] == null
                val armToken = segmentToken(worker, run.token, claim)
                val setup = TimeSource.Monotonic.markNow()
                val opened = runCatching {
                    run.handles[arm] ?: (proofTasks?.open(arm, armToken) ?: worker.newResumableSolve(armToken))
                        ?.also { run.handles[arm] = it }
                }
                val handle = opened.getOrNull()
                val openingFailure = opened.exceptionOrNull()
                if (opening && (handle != null || openingFailure != null)) {
                    claim.initializationNanos = setup.elapsedNow().inWholeNanoseconds
                    claim.initializationWork = handle?.initialWork
                        ?: (openingFailure as? SearchInitializationCancelled)?.work ?: 0L
                    run.log.initialized(
                        arm,
                        setup.elapsedNow().inWholeMilliseconds,
                        handle?.initialWork ?: (openingFailure as? SearchInitializationCancelled)?.work ?: 0L,
                        openingFailure is SearchInitializationCancelled,
                    )
                }
                val r: SolveResult?
                if (openingFailure == null) run.locked { run.firstQuery(arm) }
                val failure: Throwable?
                val work: Long
                if (openingFailure != null) {
                    r = null
                    failure = openingFailure
                    work = (openingFailure as? SearchInitializationCancelled)?.work ?: 0L
                } else if (handle != null) {
                    val workBefore = handle.work - if (opening) handle.initialWork else 0L
                    val instructionsBefore = if (worker.acceptsInstructionBudget) handle.stats.ls.moves.sum else 0.0
                    val outcome = runCatching {
                        val millis = remainingMillis(armToken)
                        if (worker.acceptsInstructionBudget && handle is InstructionSlicedSolve) {
                            handle.runInstructionSlice(run.token, millis, instructionsOf(claim))
                        } else {
                            handle.runSlice(run.token, millis, claim.handleNodes)
                        }
                    }
                    r = outcome.getOrNull()
                    failure = outcome.exceptionOrNull()
                    work = if (worker.acceptsInstructionBudget) {
                        countedInstructions(claim, handle.stats.ls.moves.sum - instructionsBefore)
                    } else {
                        handle.work - workBefore
                    }
                } else {
                    require(diagnostics == null || worker.acceptsInstructionBudget) {
                        "diagnostic scheduling requires a counted or resumable worker: ${worker.label}"
                    }
                    val outcome = runCatching { worker.solve(armToken, instructionsOf(claim)) }
                    r = outcome.getOrNull()
                    failure = outcome.exceptionOrNull()
                    work = countedWork(claim, r?.stats)
                }
                val failed = failure != null && failure !is SearchInitializationCancelled
                // A failing arm leaves the others to answer; an unsound one answered wrongly, so it is quarantined too.
                if (failure is UnsoundnessException) claim.fault = failure.message
                val checkingStarted = TimeSource.Monotonic.markNow()
                val verification = when (r) {
                    is SolveResult.Sat -> verifyWitness(worker, r.assignment, null)
                    is SolveResult.Unsat -> evidenceVerification?.let { verifier ->
                        worker.evidenceInvocation?.let { invocation ->
                            verifier.proof(worker, EvidenceKind.Infeasible, CompletedEngineResult.Solve(invocation, r))
                        } ?: Verification.Indeterminate("worker completion has no runtime invocation")
                    }
                    else -> null
                }
                claim.verified(verification)
                if (verification != null) claim.checkingNanos += checkingStarted.elapsedNow().inWholeNanoseconds
                claim.closedProof = !claim.indeterminate && claim.fault == null && r is SolveResult.Unsat
                if (!claim.indeterminate && claim.fault == null && r is SolveResult.Sat) {
                    claim.improved = true
                    claim.acceptedIncumbents = 1
                }
                run.locked {
                    run.record(
                        claim,
                        handle?.stats ?: (failure as? SearchInitializationCancelled)?.stats ?: r?.stats,
                        cumulative = handle != null,
                        work = work,
                        failed = failed,
                        failure = failure,
                        phase = when {
                            openingFailure != null -> "opening"
                            handle != null -> "slice"
                            else -> "one-shot"
                        },
                    )
                    if (claim.fault != null) {
                        run.quarantine(claim)
                        return@locked
                    }
                    if ((r is SolveResult.Sat || r is SolveResult.Unsat) && !claim.indeterminate && decided == null) {
                        decided = r
                        run.finish()
                    }
                    // An arm that threw is retired like one that finished: rescheduling it would only fail again.
                    if (failure != null || (handle != null && r != null) ||
                        (claim.indeterminate && (r is SolveResult.Sat || r is SolveResult.Unsat))
                    ) {
                        r?.let(verdicts::add)
                        run.retire(arm)
                    }
                }
            }
        }
        val cleanup = runCatching { proofTasks?.close() }
        lastBooleanProofTasks = proofTasks?.size ?: 0
        lastBooleanProofCompleted = proofTasks?.completed ?: 0
        lastBooleanProofWork = proofTasks?.work ?: 0L
        lastBooleanProofProbeWork = proofTasks?.probeWork ?: 0L
        lastBooleanProofProbeSegments = proofTasks?.probeSegments ?: 0
        lastBooleanProofPartitionLiteral = proofTasks?.partitionLiteral
        execution.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.takeUnless { it === failure }?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
        val stats = run.folded()
        return when (val r = decided) {
            is SolveResult.Sat -> r.copy(stats = stats)

            is SolveResult.Unsat -> r.copy(stats = stats)

            else -> if (run.allRetired) {
                SolveResult.Unknown(unsettledReason(verdicts), stats)
            } else {
                SolveResult.Unknown(if (run.workLimitReached) TerminationReason.BudgetExhausted
                    else TerminationReason.Cancelled, stats)
            }
        }
    }

    internal var exactLinearObjective: LinearObjective? = null

    /**
     * Branch-and-bound: run arms in scheduled segments, carrying one shared incumbent. Each segment streams
     * against its arm's own objective representation, sees the shared bound (backtrack prunes on it) and the
     * incumbent assignment (local search warm-starts from it). Returns [MinimizeResult.Optimal] /
     * [MinimizeResult.Infeasible] only when an arm exhausts its search, otherwise the best incumbent as
     * [MinimizeResult.BestFound]. `onImprovement` fires once per strict global improvement, tagged with the arm
     * that produced it, in the order the incumbent installed them.
     */
    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        val exact = exactLinearObjective
        return if (exact == null) minimize(cancellation, onImprovement, PortfolioIncumbents.floating())
        else minimize(cancellation, onImprovement, PortfolioIncumbents.exact(exact))
    }

    internal fun <V> minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
        incumbents: PortfolioIncumbents<V>,
        terminalCheck: PortfolioTerminalCheck? = null,
        onAccepted: ((AcceptedPortfolioIncumbent<V>) -> Unit)? = null,
    ): MinimizeResult = qualify(minimizeRun(cancellation, onImprovement, incumbents, terminalCheck, onAccepted))

    @Suppress("TooGenericExceptionCaught")
    private fun <V> minimizeRun(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
        incumbents: PortfolioIncumbents<V>,
        terminalCheck: PortfolioTerminalCheck?,
        onAccepted: ((AcceptedPortfolioIncumbent<V>) -> Unit)?,
    ): MinimizeResult {
        val run = Schedule(cancellation, arrayOfNulls<ResumableSearch>(workers.size))
        val incumbent = incumbents.exchange
        if (incumbent.current() != null) {
            run.endFeasibilityCoverage()
            run.incumbentImproved()
            run.startImprovementPhase()
            run.incumbentAvailable(incumbent.current()?.version ?: 0)
        }
        val pending = MutableList<Candidate<MinimizeResult.WithSample, V>?>(workers.size) { null }
        val start = TimeSource.Monotonic.markNow()
        val observedCutoffs = arrayOfNulls<Double>(workers.size)
        val staleCredits = DoubleArray(workers.size)
        var callbackFailure: Throwable? = null
        var unbounded: MinimizeResult.Unbounded? = null
        var exhausted = false
        var terminalPublished = false
        var acceptedVersion = 0L

        // Install a strictly-improving incumbent and credit it to [claim]'s arm. Called under the run's lock, so the
        // check, the callback and the install are one step: concurrent lanes report improvements in the order they
        // installed and never report one a peer already beat.
        fun install(claim: Claim, candidate: Candidate<MinimizeResult.WithSample, V>) {
            val r = qualify(candidate.assignment)
            val before = incumbent.current()
            if (!incumbents.isImproving(candidate.objective)) return
            val worker = workers[claim.arm]
            if (incumbent.offer(r.sample, candidate.objective) !is Publication.Installed) return
            claim.acceptedIncumbents++
            run.endFeasibilityCoverage()
            run.incumbentImproved()
            run.incumbentAvailable(incumbent.current()?.version ?: 0)
            try {
                acceptedVersion++
                onAccepted?.invoke(AcceptedPortfolioIncumbent(acceptedVersion, r.sample, candidate.objective))
                onImprovement?.invoke(AttributedImprovement(worker.label, worker.armId, start.elapsedNow(), r))
            } catch (failure: Throwable) {
                callbackFailure = failure
                run.finish()
                throw failure
            }
            claim.improved = true
            if (before != null) {
                if (claim.hadIncumbent) {
                    val reward = incumbents.gain(before.objective, candidate.objective)
                    run.ledgerFor(claim.arm).credit(claim.arm, Signal.Improvement, reward)
                }
            } else if (!claim.foundFirst) {
                claim.foundFirst = true
                run.ledgerFor(claim.arm).credit(claim.arm, Signal.FirstSolution, 1.0)
            }
        }

        // Check [claim]'s waiting candidate: refuted, it marks the arm faulty; verified, it goes to [install]. Checked
        // outside the lock: re-deriving a whole assignment is the costly part, and peers need not wait.
        fun check(claim: Claim) {
            val candidate = pending[claim.arm] ?: return
            pending[claim.arm] = null
            val r = candidate.assignment
            if (claim.fault != null || !incumbents.isImproving(candidate.objective)) return
            val started = TimeSource.Monotonic.markNow()
            val verification = verifyWitness(workers[claim.arm], r.sample, r.objective)
            claim.verified(verification)
            claim.checkingNanos += started.elapsedNow().inWholeNanoseconds
            if (verification !is Verification.Accepted) return
            claim.checked(started.elapsedNow())
            run.locked { install(claim, candidate) }
        }

        // Offer [claim]'s arm's candidate. Re-deriving a whole assignment can cost far more than the search took to
        // find it, and on a model whose incumbents come a millisecond apart a check on each one leaves the arm
        // verifying instead of searching. So the best candidate waits, and is checked once checks have taken no more
        // than [CHECK_SHARE] of the arm's time; whatever still waits is checked as the segment ends.
        fun accept(claim: Claim, r: MinimizeResult.WithSample) {
            if (claim.fault != null) return
            val value = incumbents.valueOf(r) ?: return
            if (!incumbents.isImproving(value)) return
            val waiting = pending[claim.arm]
            if (waiting == null || incumbents.improves(value, waiting.objective)) {
                pending[claim.arm] = Candidate(r, value)
            }
            if (diagnostics != null || claim.checkDue()) check(claim)
        }

        run.execute { claim ->
            val evaluationStarted = runTimeSource.markNow()
            val arm = claim.arm
            val worker = workers[arm]
            claim.hadIncumbent = incumbent.current() != null
            val opening = run.handles[arm] == null
            val armToken = segmentToken(worker, run.token, claim)
            val workerBound = {
                val current = incumbent.current()
                val cutoff = incumbents.bound(current)
                observedCutoffs[arm] = cutoff.takeIf(Double::isFinite)
                if (current != null) run.incumbentRead(arm, current.version)
                cutoff
            }
            val setup = TimeSource.Monotonic.markNow()
            val opened = runCatching {
                run.handles[arm] ?: worker.newResumableSearch(workerBound, armToken, incumbent.current()?.assignment)
                    ?.also { run.handles[arm] = it }
            }
            val handle = opened.getOrNull()
            val openingFailure = opened.exceptionOrNull()
            if (opening && (handle != null || openingFailure != null)) {
                claim.initializationNanos = setup.elapsedNow().inWholeNanoseconds
                claim.initializationWork = handle?.initialWork
                    ?: (openingFailure as? SearchInitializationCancelled)?.work ?: 0L
                run.log.initialized(
                    arm,
                    setup.elapsedNow().inWholeMilliseconds,
                    handle?.initialWork ?: (openingFailure as? SearchInitializationCancelled)?.work ?: 0L,
                    openingFailure is SearchInitializationCancelled,
                )
            }
            claim.preparing = handle?.preparationPending == true
            if (openingFailure == null) run.locked { run.firstQuery(arm) }
            var terminal: MinimizeResult? = null
            val failure: Throwable?
            val work: Long
            if (openingFailure != null) {
                failure = openingFailure
                work = (openingFailure as? SearchInitializationCancelled)?.work ?: 0L
            } else if (handle != null) {
                val workBefore = handle.work - if (opening) handle.initialWork else 0L
                val instructionsBefore = if (worker.acceptsInstructionBudget) {
                    (handle as? InstructionSlicedSearch)?.chargedInstructions ?: handle.stats.ls.moves.sum.toLong()
                } else {
                    0L
                }
                // A terminal verdict means the arm finished; null means the slice ended with the search paused.
                val outcome = runCatching {
                    val millis = remainingMillis(armToken)
                    if (worker.acceptsInstructionBudget && handle is InstructionSlicedSearch) {
                        handle.runInstructionSlice(run.token, millis, instructionsOf(claim)) { accept(claim, it) }
                    } else {
                        handle.runSlice(run.token, millis, claim.handleNodes) { accept(claim, it) }
                    }
                }
                terminal = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
                work = if (worker.acceptsInstructionBudget) {
                    val instructions = (handle as? InstructionSlicedSearch)?.chargedInstructions
                        ?: handle.stats.ls.moves.sum.toLong()
                    countedInstructions(claim, (instructions - instructionsBefore).toDouble())
                } else {
                    handle.work - workBefore
                }
            } else {
                // Local-search segments restart from the shared incumbent, bounded by their own counted work.
                require(diagnostics == null || worker.acceptsInstructionBudget) {
                    "diagnostic scheduling requires a counted or resumable worker: ${worker.label}"
                }
                failure = runCatching {
                    for (r in worker.improvements(
                        workerBound,
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
            val failed = failure != null && failure !is SearchInitializationCancelled
            if (failure is UnsoundnessException) claim.fault = failure.message
            (terminal as? MinimizeResult.WithSample)?.let { accept(claim, it) }
            check(claim)
            val unrestrictedExhaustion = observedCutoffs[arm] == null &&
                worker.family == ArmFamily.Backtrack && !worker.improvementOnly && !worker.auxiliaryProof &&
                worker.permitsSourceProofs && worker.controlsObjectiveCutoff
            if (unrestrictedExhaustion) {
                terminal = when (val completed = terminal) {
                    is MinimizeResult.Unknown -> if (completed.reason == TerminationReason.SearchExhausted &&
                        incumbent.current() == null
                    ) MinimizeResult.Infeasible(stats = completed.stats) else completed
                    is MinimizeResult.BestFound -> if (completed.reason == TerminationReason.SearchExhausted) {
                        MinimizeResult.Optimal(completed.sample, completed.objective, completed.stats,
                            evidence = completed.evidence, semantics = completed.semantics)
                    } else completed
                    else -> completed
                }
            }
            if (terminal is MinimizeResult.Infeasible && incumbent.current() != null) {
                claim.fault = "claimed infeasibility while the pool holds a verified solution"
            }
            val proofKind = when {
                terminal is MinimizeResult.Unbounded -> EvidenceKind.Unbounded
                terminal is MinimizeResult.Infeasible -> EvidenceKind.Infeasible
                PortfolioReduction.isExhausted(terminal) -> EvidenceKind.Bound
                else -> null
            }
            if (proofKind != null) {
                val checkingStarted = TimeSource.Monotonic.markNow()
                val completed = terminal?.let { actual -> worker.evidenceInvocation?.let { invocation ->
                    CompletedEngineResult.Minimize(invocation, actual, executionCutoff = observedCutoffs[arm])
                } }
                claim.verified(evidenceVerification?.let { verifier ->
                    if (completed != null) verifier.proof(worker, proofKind, completed)
                    else Verification.Indeterminate("worker completion has no runtime invocation")
                })
                claim.checkingNanos += checkingStarted.elapsedNow().inWholeNanoseconds
            }
            claim.trialTime = evaluationStarted.elapsedNow()
            run.locked {
                val checkingStarted = TimeSource.Monotonic.markNow()
                val publication = if (!terminalPublished && !failed && claim.fault == null) {
                    terminalCheck?.check(worker, handle)
                } else null
                claim.verified(publication?.verification)
                if (publication != null) claim.checkingNanos += checkingStarted.elapsedNow().inWholeNanoseconds
                claim.closedProof = !claim.indeterminate && claim.fault == null && !failed &&
                    (PortfolioReduction.isExhausted(terminal) || terminal is MinimizeResult.Unbounded ||
                        publication?.verification is Verification.Accepted)
                val stats = handle?.stats ?: (failure as? SearchInitializationCancelled)?.stats ?: terminal?.stats
                run.record(
                    claim,
                    stats,
                    cumulative = handle != null,
                    work = work,
                    failed = failed,
                    failure = failure,
                    phase = when {
                        openingFailure != null -> "opening"
                        handle != null -> "slice"
                        else -> "one-shot"
                    },
                )
                if (claim.fault != null) {
                    run.quarantine(claim)
                    return@locked
                }
                if (!terminalPublished && publication?.verification is Verification.Accepted) {
                    try {
                        publication.accepted()
                        terminalPublished = true
                    } catch (failure: Throwable) {
                        callbackFailure = failure
                        run.finish()
                        throw failure
                    }
                    run.finish()
                }
                if (claim.foundFirst) run.startImprovementPhase()
                if (claim.improved && (handle as? ReseedGuard)?.canContinuePreparation == true) {
                    run.continuePreparation(arm)
                }
                if (terminal == null && !failed && (handle as? ReseedGuard)?.incumbentTrialPending == true) {
                    run.continuePreparation(arm)
                }
                if (handle != null && terminal == null && !failed &&
                    (handle.preparationPending ||
                        (worker.family == ArmFamily.LocalSearch && stats?.ls?.moves?.sum == 0.0))
                ) {
                    run.revisitPreparation(arm)
                }
                if (handle != null && (worker.family.observable || worker.restartingSearch) &&
                    terminal == null && !failed &&
                    incumbent.current() != null && run.reseedAllowed(arm)
                ) {
                    if (claim.improved) {
                        staleCredits[arm] = 0.0
                    } else if (work > 0L && (handle as? ReseedGuard)?.canReseed != false &&
                        reseedStaleThreshold > 0
                    ) {
                        staleCredits[arm] += if (!claim.whole && worker.family == ArmFamily.Backtrack) {
                            minOf(work.toDouble() / baseSliceWork, 1.0)
                        } else 1.0
                        if (staleCredits[arm] >= reseedStaleThreshold) {
                            val closed = runCatching { run.closeHandle(arm) }
                            closed.onFailure {
                                run.log.failure(arm, it, "close")
                                run.retire(arm)
                            }
                            if (closed.isSuccess) {
                                run.log.reseeded(arm)
                                diagnostics?.onEvent?.invoke(PortfolioEvent.Reseeded(arm))
                            }
                            staleCredits[arm] = 0.0
                        }
                    }
                }
                // A ray proves the model unbounded whatever bound the arm ran under.
                (terminal as? MinimizeResult.Unbounded)?.let {
                    if (!claim.indeterminate) {
                        if (unbounded == null) unbounded = it
                        run.finish()
                    }
                }
                // A clean segment exhaustion ends the run: any incumbent is optimal, else infeasible.
                if (!claim.indeterminate && PortfolioReduction.isExhausted(terminal)) {
                    exhausted = true
                    run.finish()
                }
                // An arm that threw is retired like one that finished: rescheduling it would only fail again.
                if (failure != null || (handle != null && terminal != null) ||
                    (
                        claim.indeterminate &&
                        (PortfolioReduction.isExhausted(terminal) || terminal is MinimizeResult.Unbounded)
                    )
                ) {
                    run.retire(arm)
                }
            }
        }
        val stats = run.folded()
        unbounded?.let { return it.copy(stats = stats) }
        // Cancellation or retirement stopped a still-open search: keep the incumbent (BestFound) or report Unknown.
        return PortfolioReduction.terminal(incumbents.projected(), dirty = !exhausted, stats)
    }

    private fun verifyWitness(worker: PortfolioWorker, sample: Sample, value: Double?): Verification<Sample, Double?> {
        evidenceVerification?.let { return it.witness(worker, sample, value) }
        val reason = witnessCheck?.refute(sample, value)
        return if (reason == null) Verification.Accepted(Candidate(sample, value)) else Verification.Rejected(reason)
    }

    /** One segment's assignment: the arm a lane claimed and what it may spend, plus what its segment found. A
     *  [whole] segment is the arm's share of the entire solve, run on a lane of its own until the run ends. */
    private class Claim(
        val arm: Int,
        val probing: Boolean,
        val sliceMillis: Long,
        val sliceWork: Long,
        val whole: Boolean = false,
        val probeFraction: Double = 1.0,
        val preparationRevisit: Boolean = false,
        val segment: Long = 0L,
    ) {
        /** The work a resumable handle's slice may spend; a negative allowance leaves it to the run's token. */
        val handleNodes: Long get() = if (whole) -1L else sliceWork

        /** When the lane claimed the segment. */
        val started = TimeSource.Monotonic.markNow()

        var indeterminate = false
        var verificationWork = 0L
            private set
        var checkingNanos = 0L
        var closedProof = false
        var acceptedIncumbents = 0L
        var initializationNanos = 0L
        var initializationWork = 0L
        var selectedPhase = PortfolioPhase.FEASIBILITY

        fun verified(verdict: Verification<*, *>?) {
            if (verdict != null && verificationWork < Long.MAX_VALUE) verificationWork++
            when (verdict) {
                is Verification.Rejected -> fault = "claimed evidence the problem refutes: ${verdict.reason}"
                is Verification.Indeterminate -> indeterminate = true
                else -> Unit
            }
        }

        var hadIncumbent = false
        var preparing = false
        var trialTime = Duration.ZERO
        var improved = false
        var foundFirst = false

        // Why the segment's claim was refuted, when it was; the arm is quarantined as the segment settles.
        var fault: String? = null

        // When the segment's next candidate check is due.
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
     * handle is advanced only by the lane holding its arm; preparation hints are read only while the arm is idle.
     */
    @Suppress("TooGenericExceptionCaught")
    private inner class Schedule<H : AutoCloseable>(cancellation: Cancellation, val handles: Array<H?>) {
        private var availability: ((Int) -> Boolean)? = null

        fun restrictAvailability(available: (Int) -> Boolean) { availability = available }
        private val started = runTimeSource.markNow()
        private val epoch = invocationStarted ?: started
        private val lock = (if (lanes > 1) Concurrency.Strict else Concurrency.None).lock()
        private val stopped = AtomicBoolean(false)

        /** The run's token: the caller's, stopped early once any lane settles the run. */
        private val external = if (diagnostics == null) cancellation else object : SchedulingCancellation {
            override val workScheduled: Boolean = true
            override fun isCancelled(): Boolean = cancellation()
            override fun workMeter() = cancellation.workMeter()
        }
        val token: Cancellation = external.alsoStoppedBy(stopped)
        private val earnable = workers.map { it.signals }
        val ledger = RewardLedger(workers.size) { arm, signal ->
            !workers[arm].auxiliaryProof && signal in earnable[arm]
        }
        private val proofLedger = if (workers.any { it.auxiliaryProof }) {
            RewardLedger(workers.size) { arm, signal -> workers[arm].auxiliaryProof && signal in earnable[arm] }
        } else null

        fun ledgerFor(arm: Int): RewardLedger = if (workers[arm].auxiliaryProof) checkNotNull(proofLedger) else ledger
        private val proofProgress = proofLedger?.let { ProgressCredit(workers.size) }
        private val progress = ProgressCredit(workers.size)
        val log = ScheduleLog(workers)
        private val progressLog = log.progress
        private val queried = BooleanArray(workers.size)
        private val recorded = BooleanArray(workers.size)
        private val readVersions = LongArray(workers.size) { -1L }
        private var initialIncumbentVersion = 0L
        private var executing = false

        fun incumbentRead(arm: Int, version: Long) {
            // One lane owns a worker's reads; unchanged cutoff polling need not acquire the scheduler lock.
            if (readVersions[arm] != version) locked {
                lifecycle(arm, PortfolioLifecycleKind.INCUMBENT_READ, version)
            }
        }

        fun lifecycle(arm: Int, kind: PortfolioLifecycleKind, version: Long = 0) {
            if (kind == PortfolioLifecycleKind.INCUMBENT_READ) {
                if (version == readVersions[arm]) return
                readVersions[arm] = version
            }
            val nanos = epoch.elapsedNow().inWholeNanoseconds
            progressLog.observe(arm, kind, nanos)
            val observedVersion = if (version == 0L &&
                (kind == PortfolioLifecycleKind.INCUMBENT_IMPORTED || kind == PortfolioLifecycleKind.INCUMBENT_CONSUMED)
            ) readVersions[arm].coerceAtLeast(0L) else version
            onProgress?.invoke(PortfolioEvent.Lifecycle(arm, workers[arm].label, kind, nanos, observedVersion))
        }

        fun firstQuery(arm: Int) {
            if (queried[arm]) return
            queried[arm] = true
            lifecycle(arm, PortfolioLifecycleKind.FIRST_QUERY)
        }

        fun incumbentAvailable(version: Long) {
            if (!executing) {
                initialIncumbentVersion = version
                return
            }
            for (arm in workers.indices) if (!retired[arm]) {
                lifecycle(arm, PortfolioLifecycleKind.INCUMBENT_AVAILABLE, version)
            }
        }

        private val completedStats = arrayOfNulls<SolveStats>(workers.size)
        private val liveStats = arrayOfNulls<SolveStats>(workers.size)
        private val busy = BooleanArray(workers.size)
        private val retired = BooleanArray(workers.size)
        private val firstWitnessArms = workers.indices.filter { workers[it].firstWitnessOnly }
        private var remaining = workers.size
        private val probed = BooleanArray(workers.size)
        private val productive = BooleanArray(workers.size)
        private val trialStarted = BooleanArray(workers.size)
        private val trialPreparationTime = Array(workers.size) { Duration.ZERO }
        private val preparationTimeLimit = baseSliceMillis.milliseconds * AUXILIARY_PREPARATION_ALLOWANCES
        private val trialSearchWork = LongArray(workers.size)
        private val trialPreparationWork = LongArray(workers.size)
        private val trialSearchTime = Array(workers.size) { Duration.ZERO }
        private val trialTimeLimit = baseSliceMillis.milliseconds * AUXILIARY_SEARCH_TIME_ALLOWANCES
        private val trialWorkLimit = maxSliceWork.coerceAtMost(Long.MAX_VALUE / AUXILIARY_SEARCH_WORK_ALLOWANCES) *
            AUXILIARY_SEARCH_WORK_ALLOWANCES
        private val preparationRevisits = BooleanArray(workers.size)
        private var continuingPreparation = -1
        private var nextPreparationArm = 0

        // Segment time each arm has run ([armNanos]) and all arms together ([totalNanos]), for the shares
        // [minShares] owes.
        private val armNanos = LongArray(workers.size)
        private val activeSince = arrayOfNulls<TimeMark>(workers.size)
        private val armWork = DoubleArray(workers.size)
        private val siblingWork = LongArray(workers.size)
        private var proofStarted = false
        private var totalNanos = 0L
        private var chargedWork = 0L
        private var segmentIndex = 0L
        private var slice = baseSliceMillis
        private var sliceWork = baseSliceWork
        private val families = FamilyPolicy(bandit.random, profile?.let(FamilyPrior::of) ?: FamilyPrior.UNIFORM)
        private var improving = false
        private var feasibilityComplete = false
        private var incumbentElapsed: DoubleArray? = null

        fun endFeasibilityCoverage() {
            feasibilityComplete = true
        }

        fun incumbentImproved() {
            incumbentElapsed = DoubleArray(workers.size) { elapsedNanos(it) }
        }

        private fun elapsedNanos(arm: Int): Double =
            if (diagnostics != null) armWork[arm] else
                armNanos[arm].toDouble() + (activeSince[arm]?.elapsedNow()?.inWholeNanoseconds ?: 0L)

        /** Whether every arm has retired. */
        val allRetired: Boolean get() = remaining == 0
        val workLimitReached: Boolean get() = diagnostics?.let { chargedWork >= it.workLimit } == true

        fun continuePreparation(arm: Int) {
            continuingPreparation = arm
        }

        fun reseedAllowed(arm: Int): Boolean = !retired[arm] &&
            !(feasibilityComplete && workers[arm].firstWitnessOnly) &&
            !(improving && arm in incumbentProbeArms && !productive[arm])

        fun revisitPreparation(arm: Int) {
            if (!improving) preparationRevisits[arm] = true
        }

        fun <T> locked(action: () -> T): T = lock.withLock { action() }

        /** End the run: every lane stops at its next poll and claims nothing more. */
        fun finish() = stopped.store(true)

        /**
         * Run [segment] on the lanes until the run is finished, cancelled, or out of arms, then close every handle
         * still open and rethrow the first failure a lane hit, a close failure suppressed into it.
         */
        fun execute(segment: (Claim) -> Unit) {
            val execution = runCatching {
                for ((arm, worker) in workers.withIndex()) {
                    worker.onLifecycle = { kind, version ->
                        if (kind == PortfolioLifecycleKind.INCUMBENT_READ) incumbentRead(arm, version)
                        else locked { lifecycle(arm, kind, version) }
                    }
                    onProgress?.invoke(PortfolioEvent.Lifecycle(arm, worker.label, PortfolioLifecycleKind.CREATED,
                        worker.createdNanos, 0))
                }
                executing = true
                if (initialIncumbentVersion > 0) incumbentAvailable(initialIncumbentVersion)
                diagnostics?.onEvent?.invoke(PortfolioEvent.Started(diagnostics.seed, diagnostics.workLimit))
                for ((arm, worker) in workers.withIndex()) if (diagnostics != null) {
                    worker.sharingMeter?.onImport = { channel, cursor, count ->
                        diagnostics.onEvent(PortfolioEvent.Imported(arm, channel, cursor, count))
                    }
                }
                parallelRun(List(lanes) { index -> { lane(index, segment) } })
                laneFailure?.let { throw it }
            }
            val primary = execution.exceptionOrNull()
            val cleanup = runCatching { closeAll(primary) }
            val failure = primary ?: cleanup.exceptionOrNull()
            for (worker in workers) {
                worker.sharingMeter?.onImport = null
                worker.onLifecycle = null
            }
            val finished = PortfolioEvent.Finished(when {
                failure != null -> "failure"
                stopped.load() && remaining > 0 -> "completed"
                remaining == 0 -> "retired"
                workLimitReached -> "work-limit"
                else -> "external-stop"
            }, chargedWork)
            val notification = runCatching {
                diagnostics?.onEvent?.invoke(finished)
                onProgress?.invoke(finished)
            }
            if (failure != null) {
                notification.exceptionOrNull()?.takeUnless { it === failure }?.let(failure::addSuppressed)
                throw failure
            }
            notification.getOrThrow()
        }

        // The first failure any lane hit; it ends the run and is rethrown once every lane has joined.
        private var laneFailure: Throwable? = null

        // Runs segments until the run ends. Never throws: a native lane cannot hand an exception back, so a failure
        // is recorded for [execute] to rethrow.
        private fun lane(index: Int, segment: (Claim) -> Unit) {
            try {
                while (!token() && (diagnostics == null || chargedWork < diagnostics.workLimit)) {
                    val claim = claim(index) ?: break
                    val started = runTimeSource.markNow()
                    try {
                        segment(claim)
                    } finally {
                        val spent = started.elapsedNow().inWholeNanoseconds
                        locked {
                            busy[claim.arm] = false
                            activeSince[claim.arm] = null
                            armNanos[claim.arm] += spent
                            progressLog.active(claim.arm, spent)
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
         * first runs one base slice, in order, so the policy starts from evidence on each admitted arm; then the
         * policy picks among arms neither busy nor retired. Improvement variants are admitted at the first
         * incumbent, or when the initial pool retires.
         */
        private fun claim(lane: Int): Claim? = locked {
            if (feasibilityComplete) {
                for (arm in firstWitnessArms) if (!busy[arm]) retire(arm)
            }
            if (remaining == 0) return@locked null
            val dedicated = diagnostics == null && lanes == workers.size && workers.none { it.startAfterIncumbent }
            val eligible = eligibleArms().let { arms -> availability?.let { arms.filter(it) } ?: arms }
            val probe = if (dedicated) null else eligible.filter { !probed[it] && !busy[it] && !retired[it] }
                .minByOrNull { if (workers[it].auxiliaryProof) 1 else 0 }
            val revisit = if (dedicated || improving || eligible.any { !probed[it] && !retired[it] }) {
                null
            } else {
                eligible.filter { preparationRevisits[it] && !busy[it] && !retired[it] }
                    .minByOrNull { (it - nextPreparationArm + workers.size) % workers.size }
            }
            val owed = if (dedicated || probe != null) null else owedArm(eligible) ?: owedCompleteArm(eligible)
            val arm = when {
                dedicated -> lane

                probe != null -> probe.also { probed[it] = true }

                owed != null -> owed

                revisit != null -> revisit

                else -> policyPick(eligible)
            }
            if (arm < 0 || retired[arm] || busy[arm]) return@locked null
            val revisiting = !dedicated && !improving && probe == null && preparationRevisits[arm]
            if (revisiting) {
                preparationRevisits[arm] = false
                nextPreparationArm = (arm + 1) % workers.size
            }
            val auxiliary = improving && arm in incumbentProbeArms && !productive[arm]
            val probing = probe != null || (auxiliary && !trialStarted[arm])
            val baseAllowance = probing || revisiting || workers[arm].auxiliaryProof
            val ordinaryAllowance = if (baseAllowance) baseSliceWork else sliceWork
            val allowance = if (diagnostics == null) ordinaryAllowance else
                minOf(ordinaryAllowance, diagnostics.workLimit - chargedWork)
            val time = if (baseAllowance) baseSliceMillis else slice
            val remainingTime = if ((handles[arm] as? ResumableSearch)?.preparationPending != false) {
                preparationTimeLimit - trialPreparationTime[arm]
            } else trialTimeLimit - trialSearchTime[arm]
            busy[arm] = true
            activeSince[arm] = runTimeSource.markNow()
            Claim(
                arm,
                probing,
                if (auxiliary) minOf(time, remainingTime.inWholeMilliseconds.coerceAtLeast(1L)) else time,
                if (auxiliary) minOf(allowance, trialWorkLimit - trialSearchWork[arm]) else allowance,
                whole = dedicated,
                probeFraction = if (probing) initialProbeFraction(arm,
                    eligible.filter { !workers[it].auxiliaryProof }, initialProbeWeights) else 1.0,
                preparationRevisit = revisiting,
                segment = segmentIndex++,
            ).also { claim ->
                claim.selectedPhase = if (feasibilityComplete) PortfolioPhase.IMPROVEMENT
                    else PortfolioPhase.FEASIBILITY
                diagnostics?.onEvent?.invoke(PortfolioEvent.Selected(claim.segment, arm, workers[arm].label,
                    claim.sliceWork))
            }
        }

        private fun eligibleArms(): List<Int> {
            val available = workers.indices.filter { !workers[it].startAfterIncumbent || feasibilityComplete }
            val first = available.filter { !workers[it].improvementOnly }
            val eligible = if (improving || first.all { retired[it] }) available else first
            val complete = first.filter { workers[it].family == ArmFamily.Backtrack && !workers[it].auxiliaryProof }
            val covered = if (proofStarted) {
                val active = complete.filter { !retired[it] }
                var needed = active.fold(0L) { work, _ -> work + minOf(baseSliceWork, Long.MAX_VALUE - work) }
                for (arm in active) needed -= minOf(needed, siblingWork[arm])
                needed == 0L
            } else complete.all { retired[it] || siblingWork[it] >= baseSliceWork }
            return if (covered) eligible else eligible.filter { !workers[it].auxiliaryProof }
        }

        // The free arm furthest below its owed share, else the policy's pick among free arms: a family first, so a
        // family's share does not grow with its arm count, then an arm of that family. An LNS bootstrap competes
        // within local search's feasibility share; a neighborhood needs an incumbent unless it is all that is left.
        private fun policyPick(eligibleArms: List<Int>): Int {
            owedArm(eligibleArms)?.let { return it }
            owedCompleteArm(eligibleArms)?.let { return it }
            eligibleArms.firstOrNull {
                workers[it].auxiliaryProof && !busy[it] && !retired[it]
            }?.let { return it }
            if (improving) {
                eligibleArms.firstOrNull {
                    it in incumbentProbeArms && trialStarted[it] && !productive[it] && !busy[it] && !retired[it]
                }?.let { return it }
            }
            owedProofArm(eligibleArms)?.let { return it }
            val preparing = continuingPreparation
            if (preparing >= 0 && !busy[preparing]) {
                if (!retired[preparing] && preparing in eligibleArms &&
                    (handles[preparing] as? ReseedGuard)?.let {
                        it.canContinuePreparation || it.incumbentTrialPending
                    } == true
                ) return preparing
                continuingPreparation = -1
            }
            val free = eligibleArms.filter { !busy[it] && !retired[it] }
            if (free.isEmpty()) return -1
            val present = free.mapTo(LinkedHashSet()) { policyFamily(it) }
            val eligible = if (improving) present else present.filter { it != ArmFamily.Lns }.ifEmpty { present }
            val family = families.choose(eligible)
            val candidates = free.filter { policyFamily(it) == family }
            // Complete-search progress can remain invisible until a leaf; shared deductions do not
            // establish which search will reach the first witness.
            if (!improving && !family.observable) return candidates.minBy { armWork[it] }
            return armAmong(candidates)
        }

        private fun owedProofArm(eligibleArms: List<Int>): Int? {
            val baseline = incumbentElapsed ?: return null
            val active = eligibleArms.filter { !retired[it] && !workers[it].auxiliaryProof }
            val complete = active.filter {
                val worker = workers[it]
                worker.family == ArmFamily.Backtrack && (!worker.restartingSearch || includeRestartingProof) &&
                    !worker.improvementOnly && worker.permitsSourceProofs && handles[it] is ResumableSearch &&
                    !(it in incumbentProbeArms && !productive[it])
            }
            val free = complete.filter { !busy[it] }
            if (free.isEmpty()) return null
            val elapsed = active.associateWith { (elapsedNanos(it) - baseline[it]).coerceAtLeast(0.0) }
            val total = elapsed.values.sum()
            // Retained exhaustive traversal can close a proof without earning a progress signal.
            // Fund it throughout the incumbent epoch so a long competing segment cannot consume its catch-up time.
            val owed = total * improvementProofShare / complete.size
            return free.minBy { elapsed.getValue(it) }.takeIf { elapsed.getValue(it) < owed }
        }

        private fun owedCompleteArm(eligibleArms: List<Int>): Int? {
            if (feasibilityComplete) return null
            val active = eligibleArms.filter { !retired[it] && !workers[it].auxiliaryProof }
            val complete = active.filter { workers[it].family == ArmFamily.Backtrack }
            val free = complete.filter { !busy[it] }
            if (free.isEmpty()) return null
            val elapsed = active.associateWith { elapsedNanos(it) }
            // LNS and local search count as one feasibility family: an LNS bootstrap competes within local search.
            val activeFamilyCount = active.map {
                if (workers[it].family.observable) ArmFamily.LocalSearch else ArmFamily.Backtrack
            }.distinct().size
            val lag = if (diagnostics != null) baseSliceWork else baseSliceMillis.milliseconds.inWholeNanoseconds
            val covered = (elapsed.values.sum() - lag).coerceAtLeast(0.0)
            val owed = covered / activeFamilyCount / complete.size
            return free.minBy { elapsed.getValue(it) }.takeIf { elapsed.getValue(it) < owed }
        }

        private fun policyFamily(arm: Int): ArmFamily {
            val family = workers[arm].family
            return if (!improving && family == ArmFamily.Lns &&
                (handles[arm] as? IncumbentBootstrapSearch)?.bootstrapPending == true
            ) {
                ArmFamily.LocalSearch
            } else {
                family
            }
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
            proofLedger?.resetPhase()
            (bandit as? DiscountedThompson)?.fade(phaseRetention)
        }

        private fun owedArm(eligibleArms: List<Int>): Int? {
            if (minShares.isEmpty() || (improving && reserveBeforeIncumbentOnly)) return null
            var owed: Int? = null
            var deficit = 0.0
            for (arm in eligibleArms) {
                if (busy[arm] || retired[arm]) continue
                val short = if (diagnostics != null) minShares[arm] * chargedWork - armWork[arm]
                    else minShares[arm] * totalNanos - armNanos[arm]
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
        @Suppress("LongParameterList")
        fun record(
            claim: Claim,
            stats: SolveStats?,
            cumulative: Boolean,
            work: Long,
            failed: Boolean,
            failure: Throwable?,
            phase: String,
        ) {
            val arm = claim.arm
            val account = ledgerFor(arm)
            if (claim.closedProof) account.credit(arm, Signal.ProofClosure, 1.0)
            siblingWork[arm] += minOf(work.coerceAtLeast(0L), Long.MAX_VALUE - siblingWork[arm])
            val proofWork = workers[arm].auxiliaryProof && work > 0L
            if ((!proofStarted && claim.improved) || proofWork) siblingWork.fill(0L)
            if (proofWork) proofStarted = true
            val searchCharge = if (work > 0L) work else claim.sliceWork
            val checking = if (diagnostics != null) claim.verificationWork else 0L
            val charge = searchCharge + minOf(checking, Long.MAX_VALUE - searchCharge)
            armWork[arm] += charge
            chargedWork += minOf(charge, Long.MAX_VALUE - chargedWork)
            diagnostics?.onEvent?.invoke(PortfolioEvent.Charged(claim.segment, arm, work, checking, charge, failed))
            if (stats != null) {
                if (cumulative) {
                    liveStats[arm] = stats
                } else {
                    completedStats[arm] = (completedStats[arm] ?: SolveStats.EMPTY).mergedWith(stats)
                }
                val observer = if (workers[arm].auxiliaryProof) checkNotNull(proofProgress) else progress
                observer.observe(account, arm, stats)
            }
            contributions?.drain { kind, origin, amount ->
                if (origin in workers.indices) ledgerFor(origin).credit(origin, kind.signal, amount)
            }
            val paidWork = if (diagnostics == null) work else charge
            val credit = account.pendingCreditOf(arm)
            val earned = account.settle(arm, paidWork)
            val settled = PortfolioProgress(arm,
                if (claim.closedProof) PortfolioPhase.PROOF_CLOSURE else claim.selectedPhase,
                work, claim.started.elapsedNow().inWholeNanoseconds, claim.verificationWork,
                claim.improved, failed, credit, earned,
                acceptedIncumbents = claim.acceptedIncumbents, refuted = claim.fault != null,
                checkingNanos = claim.checkingNanos, initializationNanos = claim.initializationNanos,
                initializationWork = claim.initializationWork,
                constructionNanos = if (!recorded[arm]) workers[arm].constructionNanos else 0,
                preparing = claim.preparing)
            recorded[arm] = true
            progressLog.record(settled, claim.checkingNanos)
            onProgress?.invoke(PortfolioEvent.Progressed(settled))
            val reward = if (failed || claim.fault != null) 0.0 else rewardFor(settled)
            if ((!failed && claim.fault == null && earned > 0.0) || claim.improved) productive[arm] = true
            val unproductiveTrial = claim.hadIncumbent && arm in incumbentProbeArms && !productive[arm] &&
                !failed && claim.fault == null
            if (unproductiveTrial) {
                trialStarted[arm] = true
                if (claim.preparing) {
                    trialPreparationTime[arm] += claim.trialTime
                    trialPreparationWork[arm] += minOf(charge, trialWorkLimit - trialPreparationWork[arm])
                }
                if (!claim.preparing) {
                    trialSearchTime[arm] += claim.trialTime
                    val trialWork = if (diagnostics == null) work else charge
                    if (trialWork > 0L) trialSearchWork[arm] += minOf(trialWork, trialWorkLimit - trialSearchWork[arm])
                }
            }
            if (!unproductiveTrial && !workers[arm].auxiliaryProof) {
                val weight = (if (failed) maxOf(paidWork, claim.sliceWork) else paidWork).toDouble() / claim.sliceWork
                bandit.update(arm, reward, weight)
                diagnostics?.onEvent?.invoke(PortfolioEvent.Rewarded(arm, reward, weight))
                val family = policyFamily(arm)
                val observableSearch = family.observable && work > 0L && !claim.preparing &&
                    failure !is SearchInitializationCancelled
                families.record(family, progressed = reward > 0.0, plateau = improving || observableSearch)
            }
            // Credit an arm earns while others run, from peers using what it shared, pays out now as one segment's
            // evidence: an arm the policy has stopped picking would otherwise hold it forever.
            for (other in workers.indices) {
                val idleAccount = ledgerFor(other)
                if (other == arm || busy[other] || retired[other] || !idleAccount.hasPending(other)) continue
                val idleCredit = idleAccount.pendingCreditOf(other)
                val idleProgress = PortfolioProgress(other,
                    if (feasibilityComplete) PortfolioPhase.IMPROVEMENT else PortfolioPhase.FEASIBILITY,
                    0, 0, 0, false, false, idleCredit, idleAccount.settleIdle(other, claim.sliceWork),
                    idleCredit = true)
                progressLog.record(idleProgress, 0)
                onProgress?.invoke(PortfolioEvent.Progressed(idleProgress))
                val idleReward = rewardFor(idleProgress)
                if (idleProgress.ordinaryReward > 0.0) productive[other] = true
                if (!workers[other].auxiliaryProof) {
                    bandit.update(other, idleReward, 1.0)
                    diagnostics?.onEvent?.invoke(PortfolioEvent.Rewarded(other, idleReward, 1.0))
                }
            }
            log.record(arm, work, claim.started.elapsedNow().inWholeMilliseconds, reward, failed)
            if (failed && failure != null && failure !is UnsoundnessException) log.failure(arm, failure, phase)
            // The probe runs at the base slice for every arm, so its cost stays flat in the arm count.
            if (!claim.probing && !claim.preparationRevisit && !unproductiveTrial &&
                !workers[arm].auxiliaryProof
            ) {
                slice = grow(slice, maxSliceMillis)
                sliceWork = grow(sliceWork, maxSliceWork)
            }
            if (unproductiveTrial && (trialSearchWork[arm] >= trialWorkLimit ||
                    (diagnostics != null && trialPreparationWork[arm] >= trialWorkLimit) ||
                    (diagnostics == null && (trialSearchTime[arm] >= trialTimeLimit ||
                        trialPreparationTime[arm] >= preparationTimeLimit)))
            ) retire(arm)
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
            lifecycle(arm, PortfolioLifecycleKind.RETIRED)
            diagnostics?.onEvent?.invoke(PortfolioEvent.Retired(arm))
            remaining--
            closeHandle(arm)
            if (remaining == 0) finish()
        }

        /** The pool's total counters, every arm that did work included, with the schedule attached. */
        fun folded(): SolveStats {
            val stats = (completedStats.filterNotNull() + liveStats.filterNotNull())
                .fold(SolveStats.EMPTY) { acc, snapshot -> acc.mergedWith(snapshot) }
            return stats.copy(
                run = stats.run.copy(wallMs = started.elapsedNow().inWholeMilliseconds),
                portfolio = log.stats(ledger, proofLedger ?: ledger).copy(
                    profile = profile, reseedStaleThreshold = reseedStaleThreshold,
                    baseSliceWork = baseSliceWork, maxSliceWork = maxSliceWork, sliceGrowth = sliceGrowth,
                    invocationNanos = epoch.elapsedNow().inWholeNanoseconds,
                ),
            )
        }

        fun closeHandle(arm: Int) {
            val handle = handles[arm] ?: return
            val closingStarted = runTimeSource.markNow()
            val closed = runCatching { handle.close() }
            progressLog.disposed(arm, closingStarted.elapsedNow().inWholeNanoseconds)
            val final = runCatching {
                when (handle) {
                    is ResumableSearch -> handle.stats
                    is ResumableSolve -> handle.stats
                    else -> null
                }
            }
            // Closing can finalize counters; each handle contributes its last snapshot exactly once.
            val stats = final.getOrNull() ?: liveStats[arm]
            if (stats != null) completedStats[arm] = (completedStats[arm] ?: SolveStats.EMPTY).mergedWith(stats)
            liveStats[arm] = null
            handles[arm] = null
            val failure = closed.exceptionOrNull()
            if (failure != null) {
                final.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            final.getOrThrow()
        }

        private fun closeAll(primaryFailure: Throwable?) {
            var closeFailure: Throwable? = null
            for (i in handles.indices) {
                try {
                    try {
                        if (!retired[i]) lifecycle(i, PortfolioLifecycleKind.RETIRED)
                    } finally {
                        closeHandle(i)
                    }
                } catch (failure: Throwable) {
                    closeFailure?.addSuppressed(failure) ?: run { closeFailure = failure }
                }
                handles[i] = null
            }
            closeFailure?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }

        private fun rewardFor(progress: PortfolioProgress): Double =
            (rewardPolicy?.reward(progress) ?: progress.ordinaryReward).also {
                require(it.isFinite() && it in 0.0..1.0) { "portfolio reward must be finite and in [0, 1]" }
            }
    }

    /** The instruction allowance a counted local-search segment of [work] units receives. */
    private fun instructionsFor(work: Long): Long = (work * lsInstructionsPerWork).toLong().coerceAtLeast(1L)

    // A whole segment runs until the run ends, so it has no allowance of its own.
    private fun instructionsOf(claim: Claim): Long =
        if (claim.whole) Long.MAX_VALUE else instructionsFor(claim.sliceWork)

    private fun countedWork(claim: Claim, stats: SolveStats?): Long {
        val worker = workers[claim.arm]
        val instructions = stats?.ls?.moves?.sum ?: 0.0
        return when {
            claim.whole -> countedInstructions(claim, instructions)

            worker.acceptsInstructionBudget && worker.family == ArmFamily.LocalSearch ->
                countedInstructions(claim, instructions)

            // ALNS charges its outer repair allowances independently of the inner solver's move count.
            else -> claim.sliceWork
        }
    }

    private fun countedInstructions(claim: Claim, instructions: Double): Long {
        val spent = (instructions / lsInstructionsPerWork).toLong()
        return when {
            claim.whole -> spent
            instructions >= instructionsOf(claim) -> claim.sliceWork
            else -> minOf(spent, claim.sliceWork)
        }
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
        if (diagnostics != null) return cancellation
        if (claim.whole) return cancellation
        val slice = if (claim.probing && worker.acceptsInstructionBudget) probeSliceMillis else claim.sliceMillis
        return until(slice) or cancellation.shorten(remainingShare(claim))
    }

    // The offered segment deadline also bounds search after atomic handle construction.
    private fun remainingMillis(token: Cancellation): Long = token.deadline()?.let {
        (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
    } ?: Long.MAX_VALUE

    /**
     * The share of the time the run has left one segment may take: half of it split between the families the
     * policy chooses among, since the policy shares time by family and an arm's segments add up to its family's.
     * A segment cut to a share per arm would chop a proof into pieces too short to finish however much of the run
     * its family won. Probes run before the policy chooses among newly admitted arms, so each takes a share per
     * admitted arm by its probe weight, and the probes together never take more than half the run.
     */
    private fun remainingShare(claim: Claim): Double =
        if (claim.probing) REMAINING_SHARE * claim.probeFraction else REMAINING_SHARE / familyCount

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
            profile: ProblemProfile? = null,
            witnessCheck: WitnessCheck? = null,
            onFault: ((ArmFault) -> Unit)? = null,
            minShares: DoubleArray = DoubleArray(0),
            maxSliceWork: Long = 150_000,
            diagnostics: PortfolioDiagnostics? = null,
            rewardPolicy: PortfolioRewardPolicy? = null,
            onProgress: ((PortfolioEvent) -> Unit)? = null,
        ): Portfolio = Portfolio(
            workers = workers,
            bandit = DiscountedThompson(workers.size, Random(diagnostics?.seed ?: seed), halfLife),
            lanes = lanes,
            baseSliceMillis = baseSliceMillis,
            maxSliceMillis = maxSliceMillis,
            sliceGrowth = sliceGrowth,
            reseedStaleThreshold = reseedStaleThreshold,
            baseSliceWork = baseSliceWork,
            maxSliceWork = maxSliceWork,
            probeSliceMillis = probeSliceMillis,
            phaseRetention = phaseRetention,
            profile = profile,
            witnessCheck = witnessCheck,
            onFault = onFault,
            minShares = minShares,
            diagnostics = diagnostics,
            rewardPolicy = rewardPolicy,
            onProgress = onProgress,
        )
    }
}

// The share of the time a run has left that its arms' next segments may take together; see `Portfolio.segmentToken`.
private const val REMAINING_SHARE = 0.5

private const val AUXILIARY_SEARCH_WORK_ALLOWANCES = 12
private const val AUXILIARY_SEARCH_TIME_ALLOWANCES = 4
private const val AUXILIARY_PREPARATION_ALLOWANCES = 12

// Most of an arm's time incumbent checks may take; see `Portfolio.minimize`.
private const val CHECK_SHARE = 0.2

// The caller's token that also stops on [flag], keeping the caller's deadline.
private fun Cancellation.alsoStoppedBy(flag: AtomicBoolean): Cancellation =
    cancelledWhen(this::deadline, this::workScheduled) { flag.load() || this() }
