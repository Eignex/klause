package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.RepairEndpoint
import com.eignex.klause.localsearch.RepairTrace
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.result.AlnsStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.checkPointRestrictions
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.RouletteWheelBandit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Adaptive Large Neighborhood Search over an inner [Optimizer] (S. Ropke and D. Pisinger, "An Adaptive Large
 * Neighborhood Search Heuristic for the Pickup and Delivery Problem with Time Windows", Transportation Science
 * 40(4), 2006, https://doi.org/10.1287/trsc.1050.0135). Each iteration independent bandits pick a destroy and a
 * repair operator, a randomized fraction of the variables is freed and the rest pinned via [Assumptions], and
 * both bandits are rewarded by whether the repair beat the global best, was accepted, or was rejected. Rewards
 * reach the bandits unchanged, so a bandit expecting Bernoulli probabilities needs rewards in `[0, 1]`.
 *
 * [LocalSearchParams.maxInstructions] also bounds the outer loop: each iteration is charged [flipsPerIteration]
 * units whatever its repair spent, and the last iteration's `maxFlips` is clipped to the remaining budget. A
 * resumable run retains both bootstrap searches until its first incumbent, then the incumbent, acceptance policy
 * and CP repair session across slices.
 */
internal class Alns(
    val inner: Optimizer<LocalSearchParams>,
    val destroyOperators: List<DestroyOperator> = DestroyOperator.Defaults,
    val repairOperators: List<RepairOperator> = RepairOperator.Defaults,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    // A simulated-annealing temperature is only meaningful relative to the objective's magnitude, so the
    // criterion can be built from the initial incumbent's objective.
    val acceptanceFor: ((initialObjective: Double) -> AcceptanceCriterion)? = null,
    // Each iteration draws the destroyed fraction of the variables uniformly from this range, alternating small
    // refining neighbourhoods with large diversifying ones.
    val minDestroyFraction: Double = 0.1,
    val maxDestroyFraction: Double = 0.4,
    val maxIterations: Int = 50,
    val flipsPerIteration: Long = 1_000L,
    val newBestReward: Double = 3.0,
    val acceptedReward: Double = 1.0,
    val rejectedReward: Double = 0.0,
    // Drives every randomized component except caller-supplied bandits, so one seed reproduces a run.
    val rng: Random = Random.Default,
    val destroyBandit: UnivariateBandit = RouletteWheelBandit(destroyOperators.size, random = rng),
    val repairBandit: UnivariateBandit = RouletteWheelBandit(repairOperators.size, random = rng),
    // Lets repair operators keep factor weights and activity recency across iterations; activity-biased destroy
    // falls back to random without it.
    val session: LocalSearchSession? = null,
    // CP repair engine; null on a pure-LS ALNS.
    val backtrack: Optimizer<BacktrackParams>? = null,
    val backtrackParams: BacktrackParams? = null,
    // Every new best is offered to the exchange, and before each iteration ALNS adopts an unseen installed
    // incumbent that beats its own, so the next neighbourhood is destroyed from the global best. Null falls back
    // to LocalSearchParams.pooledIncumbents.
    val pooledIncumbents: IncumbentExchange<Sample, Double>? = null,
    private val literalSweep: DefinitionalSweep? = null,
) : ResumableOptimizer<LocalSearchParams> {

    init {
        require(destroyOperators.isNotEmpty()) { "Need at least one destroy operator" }
        require(repairOperators.isNotEmpty()) { "Need at least one repair operator" }
        require(destroyOperators.size == destroyBandit.nbrArms) {
            "destroyBandit arm count ${destroyBandit.nbrArms} doesn't match destroyOperators ${destroyOperators.size}"
        }
        require(repairOperators.size == repairBandit.nbrArms) {
            "repairBandit arm count ${repairBandit.nbrArms} doesn't match repairOperators ${repairOperators.size}"
        }
        require(minDestroyFraction in 0.0..1.0) { "minDestroyFraction must be in [0, 1], got $minDestroyFraction" }
        require(maxDestroyFraction in minDestroyFraction..1.0) {
            "maxDestroyFraction ($maxDestroyFraction) must be in [minDestroyFraction, 1]"
        }
    }

    override val problem: BakedProblem get() = inner.problem
    override fun solve(params: LocalSearchParams) = inner.solve(params)
    override fun samples(params: LocalSearchParams) = inner.samples(params)
    override fun enumerate(params: LocalSearchParams) = inner.enumerate(params)

    private val _iterationLog: MutableList<IterationRecord> = mutableListOf()

    // For tests and debugging; not part of the Optimizer contract.
    val iterationLog: List<IterationRecord> get() = _iterationLog

    data class IterationRecord(
        val destroyIdx: Int,
        val repairIdx: Int,
        val freedCount: Int,
        val incumbentObjective: Double,
        val newObjective: Double,
        val accepted: Boolean,
        val newBest: Boolean,
    )

    internal var incumbentToleranceCheck: ((Sample) -> Boolean)? = backtrackParams?.toleranceCheck
    internal var repairTrace: RepairTrace? = null

    private var activeSearch = false

    private inner class IncumbentSeeds(
        private val objective: LinearObjective,
        private val params: LocalSearchParams,
    ) {
        private val exchange = pooledIncumbents ?: params.pooledIncumbents
        private var seenVersion = 0L
        private val toleranceCheck = incumbentToleranceCheck
        private val assumptions = backtrackParams?.assumptions?.mergedWith(params.assumptions) ?: params.assumptions
        private var supplied = false

        fun acquire(current: Sample?, cancellation: ExecutionControl): Sample? {
            if (cancellation() || session?.depth?.let { it > 0 } == true) return null
            var best = current
            if (!supplied) {
                params.initialAssignment?.let { if (accepts(it, best, cancellation)) best = it }
                supplied = !cancellation()
            }
            if (cancellation()) return best.takeUnless { it === current }
            val fresh = exchange?.current()
            if (fresh != null && fresh.version > seenVersion) {
                // Pooled incumbents are engine results; only this worker's own pins are a new obligation.
                val point = fresh.assignment
                if (point !== current && point.numBoolVars == problem.numBoolVars &&
                    point.numIntVars == problem.numIntVars && fresh.objective.isFinite() && improves(point, best) &&
                    checkPointRestrictions(point, assumptions, cancellation) == null
                ) {
                    best = point
                }
                if (!cancellation()) seenVersion = fresh.version
            }
            return best.takeUnless { it === current }
        }

        // A caller-supplied starting point is outside every engine, so it is the one seed checked against the source.
        private fun accepts(sample: Sample, current: Sample?, cancellation: ExecutionControl): Boolean {
            val checked = finiteWitnessVerifier(problem, objective, assumptions, cancellation, toleranceCheck)
                .verify(Candidate(sample, null))
            return checked is Verification.Accepted && !cancellation() && improves(sample, current)
        }

        private fun improves(sample: Sample, current: Sample?): Boolean = objective.evaluate(sample).isFinite() &&
            (current == null || objective.evaluateExact(sample) < objective.evaluateExact(current))

        fun publish(sample: Sample, value: Double) {
            val target = exchange ?: return
            if (target.current()?.assignment !== sample) target.offer(sample, value)
        }
    }

    override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
        minimizeRun(objective, params).withSourceSemantics(problem.semantics)

    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    private fun minimizeRun(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
        check(!activeSearch) { "an ALNS handle is active" }
        _iterationLog.clear()
        val telemetry = AlnsStatsSink()
        val seeds = IncumbentSeeds(objective, params)
        val initialSample = seeds.acquire(null, params.cancellation) ?: run {
            if (params.cancellation()) return MinimizeResult.Unknown(TerminationReason.Cancelled)
            val initialResult = bootstrapIncumbent(objective, params, telemetry)
            initialResult.assignment ?: return withTelemetry(initialResult, telemetry.snapshot())
        }
        val search = NeighborhoodSearch(objective, params, initialSample, telemetry, seeds)
        var primaryFailure: Throwable? = null
        try {
            return search.advance(params)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                search.close()
            } catch (closeFailure: Throwable) {
                primaryFailure?.addSuppressed(closeFailure) ?: throw closeFailure
            }
        }
    }

    override fun resumable(objective: LinearObjective, params: LocalSearchParams): ResumableSearch {
        check(!activeSearch) { "an ALNS handle is active" }
        _iterationLog.clear()
        activeSearch = true
        return RetainedSearch(objective, params)
    }

    private inner class NeighborhoodSearch(
        private val objective: LinearObjective,
        params: LocalSearchParams,
        initialSample: Sample,
        private val telemetry: AlnsStatsSink,
        private val pooled: IncumbentSeeds,
    ) : AutoCloseable {
        var bestSample = initialSample
            private set
        var bestObj = objective.evaluate(initialSample)
            private set
        private var incumbent = bestSample
        private var incumbentObj = bestObj
        private val acceptancePolicy = acceptanceFor?.invoke(bestObj) ?: acceptance
        private var repairSearch: RepairSearch? = null
        private var tracedRepair: RepairTrace.Attempt? = null
        private var repairOpened = false
        private var closed = false
        private var currentCancellation = params.cancellation
        private val repairCancellation = cancelledWhen({ currentCancellation.deadline() },
            { currentCancellation.workScheduled() },
            { currentCancellation.stopPolling }) {
            currentCancellation() || backtrackParams?.cancellation?.invoke() == true
        }
        private var iter = 0

        init {
            pooled.publish(bestSample, bestObj)
        }

        fun adopt(sample: Sample, publish: Boolean = true): Boolean {
            val obj = objective.evaluate(sample)
            if (objective.evaluateExact(sample) >= objective.evaluateExact(bestSample)) return false
            bestSample = sample
            bestObj = obj
            incumbent = sample
            incumbentObj = obj
            if (publish) pooled.publish(sample, obj)
            return true
        }

        fun finished(params: LocalSearchParams): Boolean =
            iter >= maxIterations ||
                params.maxInstructions?.let { telemetry.outerAllowance >= it } == true ||
                params.nodeBudget?.allowance?.movesLeft() == 0L

        fun advance(
            params: LocalSearchParams,
            onIncumbent: ((MinimizeResult.BestFound) -> Unit)? = null,
        ): MinimizeResult.BestFound {
            check(!closed) { "the ALNS neighborhood search is closed" }
            currentCancellation = params.cancellation
            var initializationInterrupted = false
            if (!repairOpened && !params.cancellation()) {
                val started = TimeSource.Monotonic.markNow()
                try {
                    repairSearch = (backtrack as? BacktrackSolver)?.let { bt ->
                        backtrackParams?.let { bp ->
                            bt.openRepair(objective, bp.copy(cancellation = repairCancellation))
                        }
                    }
                    telemetry.repairCpNodes += repairSearch?.stats?.search?.nodes?.sum?.toLong() ?: 0L
                    repairOpened = true
                } catch (failure: SearchInitializationCancelled) {
                    telemetry.repairCpNodes += failure.stats.search.nodes.sum.toLong()
                    initializationInterrupted = true
                } finally {
                    telemetry.repairNanos += started.elapsedNow().inWholeNanoseconds
                }
            }
            if (initializationInterrupted) return result()
            val instructionBudget = params.maxInstructions
            var instructionsUsed = 0L
            val nodeBudget = params.nodeBudget

            while (iter < maxIterations && (instructionBudget == null || instructionsUsed < instructionBudget)) {
                if (params.cancellation() || nodeBudget?.allowance?.movesLeft() == 0L) break
                // Clipped to the remaining budget so the last iteration can't overshoot by a whole flipsPerIteration.
                val iterFlips = minOf(
                    flipsPerIteration,
                    instructionBudget?.let { it - instructionsUsed } ?: Long.MAX_VALUE,
                    nodeBudget?.allowance?.movesLeft() ?: Long.MAX_VALUE,
                )
                val perIterParams = params.copy(
                    maxFlips = iterFlips,
                    randomSeed = if (params.randomSeed != null || params.cancellation.workScheduled()) {
                        rng.nextLong()
                    } else null,
                    // The iteration's allowance is charged below, whatever its repair spends.
                    nodeBudget = null,
                )
                // Charging the declared allowance up front, whatever the destroy or repair then does, keeps
                // counting deterministic, as LocalSearchSolver's counted restart does.
                instructionsUsed += iterFlips
                telemetry.outerAllowance += iterFlips
                nodeBudget?.allowance?.spendMoves(iterFlips)
                pooled.acquire(bestSample, params.cancellation)?.let { sample ->
                    if (adopt(sample, publish = false)) onIncumbent?.invoke(result())
                }
                val destroyIdx = destroyBandit.choose()
                val repairIdx = repairBandit.choose()
                val destroyFraction = if (maxDestroyFraction > minDestroyFraction) {
                    minDestroyFraction + rng.nextDouble() * (maxDestroyFraction - minDestroyFraction)
                } else {
                    minDestroyFraction
                }
                val destroyed = destroyOperators[destroyIdx]
                    .destroy(rng, inner.problem, incumbent, objective, destroyFraction,
                        params.assumptions, params.cancellation)
                val freed = RepairScope.effective(destroyed, params.assumptions, literalSweep, params.cancellation)
                val attempt = repairTrace?.begin(
                    "alns:$destroyIdx:$repairIdx", params.randomSeed, incumbent, incumbent,
                    destroyed.bools, destroyed.ints, freed?.bools ?: intArrayOf(), freed?.ints ?: intArrayOf(),
                )
                tracedRepair = attempt
                if (attempt != null) {
                    attempt.scopeComplete = freed != null
                    val exact = if (problem.numRealVars == 0) objective.evaluateExact(bestSample) else null
                    repairTrace?.recordBest(attempt, bestObj, bestSample, exact)
                }
                if (freed == null) {
                    repairTrace?.finish(attempt, RepairEndpoint.Censored)
                    iter++
                    break
                }
                if (freed.isEmpty) {
                    repairTrace?.finish(attempt, RepairEndpoint.Unresolved)
                    destroyBandit.update(destroyIdx, rejectedReward)
                    repairBandit.update(repairIdx, rejectedReward)
                    iter++
                    continue
                }

                val pinAssumptions = RepairScope.pins(inner.problem, incumbent, freed).mergedWith(params.assumptions)
                val context = RepairContext(
                    inner, perIterParams, objective, pinAssumptions, incumbent, freed, rng, session,
                    backtrack = backtrack, backtrackParams = backtrackParams,
                    repairSearch = repairSearch, bestObjective = bestObj,
                    recordInnerWork = { nodes, moves ->
                        telemetry.repairCpNodes += nodes
                        telemetry.repairLsMoves += moves
                    },
                    recordRepairResult = attempt?.let { entry ->
                        { result -> repairTrace?.recordResult(entry, result) }
                    },
                )
                val repairStarted = TimeSource.Monotonic.markNow()
                val repaired = repairOperators[repairIdx].repair(context)
                telemetry.repairNanos += repairStarted.elapsedNow().inWholeNanoseconds
                if (repaired == null) {
                    repairTrace?.finish(attempt, RepairEndpoint.Unresolved)
                    destroyBandit.update(destroyIdx, rejectedReward)
                    repairBandit.update(repairIdx, rejectedReward)
                    iter++
                    continue
                }
                val repairedObj = objective.evaluate(repaired)
                repairTrace?.record(attempt, "repaired", repaired)
                repairTrace?.finish(attempt, RepairEndpoint.Repaired)

                val isNewBest = objective.evaluateExact(repaired) < objective.evaluateExact(bestSample)
                val accept = isNewBest || acceptancePolicy.accept(repairedObj, incumbentObj, rng)
                val reward = when {
                    isNewBest -> newBestReward
                    accept -> acceptedReward
                    else -> rejectedReward
                }
                destroyBandit.update(destroyIdx, reward)
                repairBandit.update(repairIdx, reward)
                _iterationLog.add(
                    IterationRecord(
                        destroyIdx,
                        repairIdx,
                        freed.bools.size + freed.ints.size,
                        incumbentObj,
                        repairedObj,
                        accept,
                        isNewBest,
                    ),
                )

                if (isNewBest) {
                    bestSample = repaired
                    bestObj = repairedObj
                    pooled.publish(repaired, repairedObj)
                }
                if (accept) {
                    incumbent = repaired
                    incumbentObj = repairedObj
                }
                if (isNewBest) onIncumbent?.invoke(result())
                iter++
            }

            return result()
        }

        fun result() = MinimizeResult.BestFound(
            sample = bestSample,
            objective = bestObj,
            reason = TerminationReason.BudgetExhausted,
            stats = SolveStats(alns = telemetry.snapshot()),
            semantics = problem.semantics,
        )

        override fun close() {
            if (closed) return
            closed = true
            repairTrace?.finish(tracedRepair, RepairEndpoint.Censored)
            tracedRepair = null
            repairSearch?.close()
        }
    }

    // A hybrid ALNS seeds with a budget-bounded complete solve, which reaches a first feasible fast on
    // feasibility-tight problems where local search may leave no incumbent at all. It takes a fraction of the run's
    // budget, with BOOTSTRAP_DECISIONS as the safeguard for a deadline-free run; otherwise local search seeds.
    private fun bootstrapIncumbent(
        objective: LinearObjective,
        params: LocalSearchParams,
        telemetry: AlnsStatsSink,
    ): MinimizeResult {
        val engine = backtrack
        if (engine != null) {
            val base = backtrackParams ?: BacktrackParams()
            val started = TimeSource.Monotonic.markNow()
            val cpResult = engine.minimize(
                objective,
                base.withAssumptions(params.assumptions).copy(maxDecisions = BOOTSTRAP_DECISIONS)
                    .withCancellation(params.cancellation.shorten(BT_BOOTSTRAP_FRACTION)),
            )
            telemetry.bootstrapCpMillis += started.elapsedNow().inWholeMilliseconds
            telemetry.bootstrapCpNodes += cpResult.stats.search.nodes.sum.toLong()
            if (cpResult.assignment != null) return cpResult
            if (params.cancellation()) return MinimizeResult.Unknown(TerminationReason.Cancelled)
        }
        val started = TimeSource.Monotonic.markNow()
        val result = session?.minimize(objective, params) ?: inner.minimize(objective, params)
        telemetry.bootstrapLsMillis += started.elapsedNow().inWholeMilliseconds
        telemetry.bootstrapLsMoves += result.stats.ls.moves.sum.toLong()
        return result
    }

    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private inner class RetainedSearch(
        private val objective: LinearObjective,
        private val params: LocalSearchParams,
    ) : InstructionSlicedSearch, IncumbentBootstrapSearch {
        private val telemetry = AlnsStatsSink()
        private val seeds = IncumbentSeeds(objective, params)
        private var cpBootstrap: ResumableSearch? = null
        private var lsBootstrap: ResumableSearch? = null
        private var cpAttempted = false
        private var lsAttempted = false
        private var cpFinished = backtrack == null
        private var lsFinished = false
        private var bootstrapStats = SolveStats.EMPTY
        private var neighborhood: NeighborhoodSearch? = null
        private var verdict: MinimizeResult? = null
        private var closed = false

        override var chargedInstructions = 0L
            private set

        override val isDone: Boolean get() = verdict != null
        override val bootstrapPending: Boolean get() = neighborhood == null && !closed
        override val work: Long get() = (chargedInstructions / LS_INSTRUCTIONS_PER_WORK).toLong()
        override val preparationPending: Boolean
            get() = neighborhood == null && !closed &&
                (cpBootstrap?.preparationPending == true || !lsAttempted ||
                    (lsBootstrap?.isDone == false && lsBootstrap?.stats?.ls?.moves?.sum == 0.0))

        override val stats: SolveStats
            get() {
                observeBootstrapWork()
                val base = lsBootstrap?.stats ?: bootstrapStats
                return base.copy(alns = telemetry.snapshot())
            }

        override fun runSlice(
            global: ExecutionControl,
            sliceMillis: Long,
            sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ): MinimizeResult? = runInstructionSlice(
            global, sliceMillis,
            if (sliceNodes < 0L) Long.MAX_VALUE else ceil(sliceNodes * LS_INSTRUCTIONS_PER_WORK).toLong(),
            onIncumbent,
        )

        override fun runInstructionSlice(
            global: ExecutionControl,
            sliceMillis: Long,
            sliceInstructions: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ): MinimizeResult? {
            verdict?.let { return it }
            check(!closed) { "the ALNS handle is closed" }
            require(sliceInstructions >= 0L) { "slice instructions must be non-negative" }
            val token = if (sliceMillis == Long.MAX_VALUE) {
                params.cancellation.lifetimeCancellation() or global
            } else {
                params.cancellation.lifetimeCancellation() or global or ExecutionControl.after(sliceMillis.milliseconds)
            }
            if (sliceInstructions == 0L || token()) return null
            // Finite LNS segments are charged their offered scheduling allowance, independently of inner work.
            if (sliceInstructions != Long.MAX_VALUE) charge(sliceInstructions)
            val before = telemetry.bootstrapLsMoves + telemetry.outerAllowance
            try {
                if (token()) return null
                if (neighborhood == null) {
                    seeds.acquire(null, token)?.let { acceptBootstrap(it, onIncumbent) }
                    if (neighborhood == null) bootstrapIncumbent(token, sliceInstructions, onIncumbent)
                }
                val search = neighborhood
                if (search == null) {
                    if (cpFinished && lsFinished) {
                        return finish(MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats))
                    }
                    return null
                }
                closeBootstrap()
                if (search.finished(params)) return finish(search.result())
                if (token()) return null
                val remaining = params.maxInstructions?.let {
                    (it - telemetry.outerAllowance).coerceAtLeast(0L)
                } ?: Long.MAX_VALUE
                search.advance(
                    params.copy(cancellation = token, maxInstructions = minOf(sliceInstructions, remaining)),
                    { onIncumbent(it.copy(stats = stats)) },
                )
                return if (search.finished(params)) finish(search.result()) else null
            } catch (failure: Throwable) {
                try {
                    close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            } finally {
                if (sliceInstructions == Long.MAX_VALUE) {
                    charge(telemetry.bootstrapLsMoves + telemetry.outerAllowance - before)
                }
            }
        }

        private fun bootstrapIncumbent(
            token: ExecutionControl,
            sliceInstructions: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ) {
            if (!cpFinished) {
                val cpToken = token.shorten(BT_BOOTSTRAP_FRACTION) or ExecutionControl { neighborhood != null }
                val started = TimeSource.Monotonic.markNow()
                try {
                    val engine = checkNotNull(backtrack)
                    val bp = (backtrackParams ?: BacktrackParams()).withAssumptions(params.assumptions)
                        .copy(maxDecisions = BOOTSTRAP_DECISIONS).withCancellation(cpToken)
                    if (!cpAttempted) {
                        cpAttempted = true
                        cpBootstrap = (engine as? ResumableOptimizer<BacktrackParams>)?.resumable(objective, bp)
                    }
                    val handle = cpBootstrap
                    val result = if (handle != null) {
                        handle.runSlice(cpToken, Long.MAX_VALUE) { acceptBootstrap(it.sample, onIncumbent) }
                    } else {
                        engine.minimize(objective, bp).also {
                            telemetry.bootstrapCpNodes += it.stats.search.nodes.sum.toLong()
                        }
                    }
                    result?.assignment?.let { acceptBootstrap(it, onIncumbent) }
                    cpFinished = result != null
                } finally {
                    observeBootstrapWork()
                    telemetry.bootstrapCpMillis += started.elapsedNow().inWholeMilliseconds
                }
            }
            if (neighborhood != null || token() || lsFinished) return
            val lsToken = token or ExecutionControl { neighborhood != null }
            val started = TimeSource.Monotonic.markNow()
            try {
                val lp = params.withCancellation(lsToken)
                if (!lsAttempted) {
                    lsAttempted = true
                    lsBootstrap = session?.resumable(objective, lp)
                        ?: (inner as? ResumableOptimizer<LocalSearchParams>)?.resumable(objective, lp)
                }
                val handle = lsBootstrap
                val result = when (handle) {
                    is InstructionSlicedSearch ->
                        handle.runInstructionSlice(lsToken, Long.MAX_VALUE, sliceInstructions) {
                            acceptBootstrap(it.sample, onIncumbent)
                        }
                    null -> {
                        val bounded = lp.copy(
                            maxInstructions = minOf(lp.maxInstructions ?: Long.MAX_VALUE, sliceInstructions),
                        )
                        val cold = session?.minimize(objective, bounded) ?: inner.minimize(objective, bounded)
                        bootstrapStats = cold.stats
                        telemetry.bootstrapLsMoves += cold.stats.ls.moves.sum.toLong()
                        cold
                    }
                    else -> handle.runSlice(
                        lsToken, Long.MAX_VALUE,
                        if (sliceInstructions == Long.MAX_VALUE) -1L else
                            ceil(sliceInstructions / LS_INSTRUCTIONS_PER_WORK).toLong(),
                    ) { acceptBootstrap(it.sample, onIncumbent) }
                }
                result?.assignment?.let { acceptBootstrap(it, onIncumbent) }
                lsFinished = result != null
            } finally {
                observeBootstrapWork()
                telemetry.bootstrapLsMillis += started.elapsedNow().inWholeMilliseconds
            }
        }

        private fun acceptBootstrap(sample: Sample, onIncumbent: (MinimizeResult.WithSample) -> Unit) {
            observeBootstrapWork()
            val search = neighborhood
            if (search == null) {
                neighborhood = NeighborhoodSearch(objective, params, sample, telemetry, seeds)
                onIncumbent(checkNotNull(neighborhood).result().copy(stats = stats))
            } else if (search.adopt(sample)) {
                onIncumbent(search.result().copy(stats = stats))
            }
        }

        private fun observeBootstrapWork(
            cp: ResumableSearch? = cpBootstrap,
            ls: ResumableSearch? = lsBootstrap,
        ) {
            cp?.let { telemetry.bootstrapCpNodes = it.stats.search.nodes.sum.toLong() }
            ls?.let {
                bootstrapStats = it.stats
                telemetry.bootstrapLsMoves = bootstrapStats.ls.moves.sum.toLong()
            }
        }

        private fun charge(instructions: Long) {
            chargedInstructions += minOf(instructions, Long.MAX_VALUE - chargedInstructions)
        }

        private fun finish(result: MinimizeResult): MinimizeResult {
            val completed = withStats(result, stats).withSourceSemantics(problem.semantics)
            verdict = completed
            close()
            return completed
        }

        private fun releaseBootstrap(): List<ResumableSearch?> {
            val searches = listOf(cpBootstrap, lsBootstrap)
            cpBootstrap = null
            lsBootstrap = null
            return searches
        }

        private fun closeBootstrap() {
            val bootstrap = releaseBootstrap()
            closeSearches(bootstrap) { observeBootstrapWork(bootstrap[0], bootstrap[1]) }
        }

        private fun closeSearches(searches: List<AutoCloseable?>, afterClose: () -> Unit = {}) {
            var failure: Throwable? = null
            for (search in searches) {
                try {
                    search?.close()
                } catch (closeFailure: Throwable) {
                    val primary = failure
                    if (primary == null) failure = closeFailure else primary.addSuppressed(closeFailure)
                }
            }
            try {
                afterClose()
            } catch (observationFailure: Throwable) {
                val primary = failure
                if (primary == null) failure = observationFailure else primary.addSuppressed(observationFailure)
            }
            failure?.let { throw it }
        }

        override fun close() {
            if (closed) return
            closed = true
            val bootstrap = releaseBootstrap()
            val searches = bootstrap + neighborhood
            neighborhood = null
            try {
                closeSearches(searches) { observeBootstrapWork(bootstrap[0], bootstrap[1]) }
            } finally {
                activeSearch = false
            }
        }
    }

    private fun withTelemetry(result: MinimizeResult, telemetry: AlnsStats): MinimizeResult {
        val stats = result.stats.copy(alns = result.stats.alns.mergedWith(telemetry))
        return withStats(result, stats)
    }

    private fun withStats(result: MinimizeResult, stats: SolveStats): MinimizeResult = when (result) {
        is MinimizeResult.Optimal -> result.copy(stats = stats)
        is MinimizeResult.BestFound -> result.copy(stats = stats)
        is MinimizeResult.Unbounded -> result.copy(stats = stats)
        is MinimizeResult.Infeasible -> result.copy(stats = stats)
        is MinimizeResult.Unknown -> result.copy(stats = stats)
    }

    private class AlnsStatsSink {
        var bootstrapCpNodes = 0L
        var bootstrapLsMoves = 0L
        var repairCpNodes = 0L
        var repairLsMoves = 0L
        var outerAllowance = 0L
        var bootstrapCpMillis = 0L
        var bootstrapLsMillis = 0L
        var repairNanos = 0L

        fun snapshot() = AlnsStats(
            bootstrapCpNodes, bootstrapLsMoves, repairCpNodes, repairLsMoves, outerAllowance,
            bootstrapCpMillis, bootstrapLsMillis, repairNanos / 1_000_000,
        )
    }

    private companion object {
        const val BT_BOOTSTRAP_FRACTION = 0.5

        // Bounds the bootstrap on a deadline-free run, where `shorten` cannot bound by time.
        const val BOOTSTRAP_DECISIONS = 50_000L
    }
}
