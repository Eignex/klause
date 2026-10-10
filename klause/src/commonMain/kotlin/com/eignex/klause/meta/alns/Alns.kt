package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.PooledIncumbents
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.AlnsStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.cancelledWhen
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.RouletteWheelBandit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Adaptive Large Neighborhood Search meta-optimizer on top of an arbitrary
 * [Optimizer] over [LocalSearchParams]. Each iteration:
 *
 *   1. Pick a destroy operator from [destroyOperators] via [destroyBandit] (kumulant
 *      [UnivariateBandit] — defaults to [RouletteWheelBandit]; swap in
 *      `MultiArmedBandit(BetaBernoulliTS())` for Thompson sampling or any other
 *      kumulant policy).
 *   2. Pick a repair operator from [repairOperators] via [repairBandit].
 *   3. Free a randomized fraction of the variables; pin the rest at incumbent values
 *      via [Assumptions].
 *   4. Hand the pinned problem to the chosen repair operator (typically calls
 *      `inner.minimize` with the pin set; alternative operators can vary the budget,
 *      restart cadence, or even the underlying solver).
 *   5. Reward both bandits: [newBestReward] / [acceptedReward] / [rejectedReward]
 *      depending on whether the repaired solution beats the global best, replaces the
 *      incumbent under [acceptance], or is rejected. Reward magnitudes are passed
 *      through to the bandit unchanged — callers using `BetaBernoulliTS` (which
 *      expects soft Bernoulli probabilities) should configure rewards in `[0, 1]`;
 *      the default values target `RouletteWheelBandit`'s weight-update scheme. Both
 *      bandits learn independently; the joint (destroy, repair) pair distribution
 *      emerges from their interaction.
 *
 * Compared to the [com.eignex.klause.localsearch.IteratedLocalSearchRestart] —
 * which lives inside the LS engine and re-anchors the assignment between flips — ALNS
 * is an *outer* loop: it repeatedly calls a repair operator with shrinking assumption
 * sets and lets the bandits learn which destroy/repair combo exposes the most rewarding
 * neighbourhood for this problem. Composes naturally with Session + assumption primitives.
 *
 * Specialised to [LocalSearchParams] so we can override `maxFlips` per iteration; the
 * generic-over-`P` version would need a `SolverParams.withMaxFlips` extension point.
 *
 * [LocalSearchParams.maxInstructions], when set, also bounds the outer destroy/repair loop: each
 * iteration is charged [flipsPerIteration] units — the allowance handed to that iteration's repair —
 * mirroring [com.eignex.klause.localsearch.LocalSearchSolver]'s counted restart (one transition, one
 * unit, regardless of the work it actually did). The loop stops at the first of [maxIterations],
 * the instruction budget, or [LocalSearchParams.cancellation]; the last iteration's own `maxFlips` is
 * clipped to whatever budget remains so a segment can't overshoot by a whole `flipsPerIteration`.
 * A resumable run retains both bootstrap searches until its first incumbent, then retains the
 * incumbent, acceptance policy and CP repair session across slices. Finite slices charge their offered
 * scheduling allowance separately from actual bootstrap and repair counters in [AlnsStats].
 */
internal class Alns(
    val inner: Optimizer<LocalSearchParams>,
    val destroyOperators: List<DestroyOperator> = DestroyOperator.Defaults,
    val repairOperators: List<RepairOperator> = RepairOperator.Defaults,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    /** Optional factory building the acceptance criterion from the initial incumbent's objective. A
     *  simulated-annealing temperature is only meaningful relative to the objective's magnitude, so this
     *  lets a caller scale the starting temperature to `f(initial)` (a fixed temperature would be inert on
     *  a large objective and reckless on a tiny one). When null, [acceptance] is used as given. */
    val acceptanceFor: ((initialObjective: Double) -> AcceptanceCriterion)? = null,
    /** Bounds on the destroy size, as a fraction of the total variables. Each iteration draws the fraction
     *  uniformly from `[minDestroyFraction, maxDestroyFraction]` (fixed when they are equal) — the textbook
     *  ALNS randomized degree of destruction, alternating small refining neighbourhoods with large
     *  diversifying ones so no single fixed size dominates the search. */
    val minDestroyFraction: Double = 0.1,
    val maxDestroyFraction: Double = 0.4,
    val maxIterations: Int = 50,
    val flipsPerIteration: Long = 1_000L,
    val newBestReward: Double = 3.0,
    val acceptedReward: Double = 1.0,
    val rejectedReward: Double = 0.0,
    /** Single RNG driving destroy-operator randomization, acceptance criteria, repair
     *  contexts, and the default-constructed bandits. Pass `Random(seed)` for a fully
     *  reproducible ALNS run from one seed — every randomized component derives its
     *  draws from this stream. Users supplying custom bandits are responsible for
     *  those bandits' RNGs (kumulant bandits each take a `random: Random` parameter). */
    val rng: Random = Random.Default,
    val destroyBandit: UnivariateBandit = RouletteWheelBandit(destroyOperators.size, random = rng),
    val repairBandit: UnivariateBandit = RouletteWheelBandit(repairOperators.size, random = rng),
    /** Optional session for cross-iteration state. When provided, [InnerLsRepair] (and
     *  any other repair operator that reads `context.session`) routes through it so
     *  DDFW factor weights and per-variable activity recency survive across iterations.
     *  Required to make `DestroyOperator.activityBiased(session)` useful — without a
     *  session it falls back to random. Pass `solver.session() as LocalSearchSession`. */
    val session: LocalSearchSession? = null,
    /** Optional backtrack LCG+LP engine and base params for CP repair ([BacktrackRepair]). When
     *  set, a repair operator can solve each freed fragment with full propagation + clause learning +
     *  LP bounding under the pin assumptions. Null on a pure-LS ALNS. */
    val backtrack: Optimizer<BacktrackParams>? = null,
    val backtrackParams: BacktrackParams? = null,
    /** The verified-incumbent exchange this run takes part in, both ways. Every incumbent the outer loop
     *  accepts as a new best is offered to it, and only a candidate the exchange verifies *and* finds
     *  strictly better than the standing one is installed. Before each iteration ALNS adopts a not-yet-seen
     *  installed incumbent that beats its own, so the next neighbourhood is destroyed from the globally-best
     *  assignment. Version-gated; importing is skipped when the run carries assumption pins. Null leaves the
     *  run private. */
    val pooledIncumbents: IncumbentExchange<Sample, Double>? = null,
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

    /** History snapshot exposed for tests / debugging; not part of the Optimizer contract.
     *  Read-only view — ALNS appends internally; callers may iterate but not mutate. */
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

    private var activeSearch = false

    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
        check(!activeSearch) { "an ALNS handle is active" }
        _iterationLog.clear()
        val telemetry = AlnsStatsSink()
        val initialResult = bootstrapIncumbent(objective, params, telemetry)
        val initialSample = initialResult.assignment ?: return withTelemetry(initialResult, telemetry.snapshot())
        val search = NeighborhoodSearch(objective, params, initialSample, telemetry)
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
    ) : AutoCloseable {
        var bestSample = initialSample
            private set
        var bestObj = objective.evaluate(initialSample)
            private set
        private var incumbent = bestSample
        private var incumbentObj = bestObj
        private val pooled = PooledIncumbents(
            exchange = pooledIncumbents,
            importEnabled = params.assumptions.isEmpty,
            evaluate = { objective.evaluate(it) },
        )
        private val acceptancePolicy = acceptanceFor?.invoke(bestObj) ?: acceptance
        private var repairSearch: RepairSearch? = null
        private var repairOpened = false
        private var closed = false
        private var currentCancellation = params.cancellation
        private val repairCancellation = cancelledWhen({ currentCancellation.deadline() }) {
            currentCancellation() || backtrackParams?.cancellation?.invoke() == true
        }
        private var iter = 0

        init {
            pooled.publish(bestSample, bestObj)
        }

        fun adopt(sample: Sample): Boolean {
            val obj = objective.evaluate(sample)
            if (obj >= bestObj) return false
            bestSample = sample
            bestObj = obj
            incumbent = sample
            incumbentObj = obj
            pooled.publish(sample, obj)
            return true
        }

        fun finished(params: LocalSearchParams): Boolean =
            iter >= maxIterations ||
                params.maxInstructions?.let { telemetry.outerAllowance >= it } == true ||
                params.nodeBudget?.movesLeft() == 0L

        fun advance(
            params: LocalSearchParams,
            onIncumbent: ((MinimizeResult.WithSample) -> Unit)? = null,
        ): MinimizeResult.BestFound {
            check(!closed) { "the ALNS neighborhood search is closed" }
            currentCancellation = params.cancellation
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
                } finally {
                    telemetry.repairNanos += started.elapsedNow().inWholeNanoseconds
                }
            }
            val instructionBudget = params.maxInstructions
            var instructionsUsed = 0L
            val nodeBudget = params.nodeBudget

            while (iter < maxIterations && (instructionBudget == null || instructionsUsed < instructionBudget)) {
                if (params.cancellation() || nodeBudget?.movesLeft() == 0L) break
                // This iteration's repair allowance: flipsPerIteration, clipped to whatever budget remains
                // so the last counted iteration can't overshoot by a whole flipsPerIteration.
                val iterFlips = minOf(
                    flipsPerIteration,
                    instructionBudget?.let { it - instructionsUsed } ?: Long.MAX_VALUE,
                    nodeBudget?.movesLeft() ?: Long.MAX_VALUE,
                )
                val perIterParams = params.copy(
                    maxFlips = iterFlips,
                    // Each iteration's RNG seed varies via the bandit's RNG; explicit null lets the
                    // inner solver draw its own per-call seed.
                    randomSeed = null,
                    // The iteration's allowance is charged below, whatever its repair spends.
                    nodeBudget = null,
                )
                // Charge the iteration's allowance up front — a destroy that frees nothing, or a repair
                // that rejects, still consumed the bandit picks and (for a repair op that ran) whatever
                // portion of iterFlips it used; charging the declared unit keeps counting deterministic
                // and independent of what happened inside, same as LocalSearchSolver's counted restart.
                instructionsUsed += iterFlips
                telemetry.outerAllowance += iterFlips
                nodeBudget?.spendMoves(iterFlips)
                pooled.poll(bestObj)?.let { (sample, obj) ->
                    bestSample = sample
                    bestObj = obj
                    incumbent = sample
                    incumbentObj = obj
                    onIncumbent?.invoke(result())
                }
                val destroyIdx = destroyBandit.choose()
                val repairIdx = repairBandit.choose()
                // Randomized degree of destruction: a fresh fraction each iteration (textbook ALNS).
                val destroyFraction = if (maxDestroyFraction > minDestroyFraction) {
                    minDestroyFraction + rng.nextDouble() * (maxDestroyFraction - minDestroyFraction)
                } else {
                    minDestroyFraction
                }
                val destroyed = destroyOperators[destroyIdx]
                    .destroy(rng, inner.problem, incumbent, objective, destroyFraction)
                val freed = FreedVars(
                    destroyed.bools.filterNot(params.assumptions::isFrozenBool).toIntArray(),
                    destroyed.ints.filterNot(params.assumptions::isFrozenInt).toIntArray(),
                )
                if (freed.isEmpty) {
                    destroyBandit.update(destroyIdx, rejectedReward)
                    repairBandit.update(repairIdx, rejectedReward)
                    iter++
                    continue
                }

                val pinAssumptions = buildPin(inner.problem, incumbent, freed).mergedWith(params.assumptions)
                val context = RepairContext(
                    inner, perIterParams, objective, pinAssumptions, incumbent, freed, rng, session,
                    backtrack = backtrack, backtrackParams = backtrackParams,
                    repairSearch = repairSearch, bestObjective = bestObj,
                    recordInnerWork = { nodes, moves ->
                        telemetry.repairCpNodes += nodes
                        telemetry.repairLsMoves += moves
                    },
                )
                val repairStarted = TimeSource.Monotonic.markNow()
                val repaired = repairOperators[repairIdx].repair(context)
                telemetry.repairNanos += repairStarted.elapsedNow().inWholeNanoseconds
                if (repaired == null) {
                    destroyBandit.update(destroyIdx, rejectedReward)
                    repairBandit.update(repairIdx, rejectedReward)
                    iter++
                    continue
                }
                val repairedObj = objective.evaluate(repaired)

                val isNewBest = repairedObj < bestObj
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
        )

        override fun close() {
            if (closed) return
            closed = true
            repairSearch?.close()
        }
    }

    /**
     * The first incumbent for the destroy/repair loop. For a hybrid ALNS (a backtrack engine is present)
     * seed it with a *budget-bounded complete solve*: complete search reaches a first feasible fast on both
     * easy and feasibility-tight problems — where local search flails and leaves ALNS with no incumbent at
     * all (bench-mined) — and hands over a CP-quality start. It is capped to a fraction of the run's budget
     * (via [com.eignex.klause.util.Cancellation.shorten]) so destroy/repair keeps the rest, with
     * [BOOTSTRAP_DECISIONS] as the safeguard for a budget-less (deadline-free) run. A pure-LS ALNS (no
     * backtrack engine), or a fragment the bootstrap can't seed, falls back to local search.
     */
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
    ) : InstructionSlicedSearch {
        private val telemetry = AlnsStatsSink()
        private var cpBootstrap: ResumableSearch? = null
        private var lsBootstrap: ResumableSearch? = null
        private var cpAttempted = false
        private var lsAttempted = false
        private var cpFinished = backtrack == null
        private var lsFinished = false
        private var cpClosed = false
        private var lsClosed = false
        private var bootstrapStats = SolveStats.EMPTY
        private var neighborhood: NeighborhoodSearch? = null
        private var verdict: MinimizeResult? = null
        private var closed = false

        override var chargedInstructions = 0L
            private set

        override val isDone: Boolean get() = verdict != null
        override val work: Long get() = (chargedInstructions / LS_INSTRUCTIONS_PER_WORK).toLong()
        override val preparationPending: Boolean
            get() = neighborhood == null && !closed &&
                (cpBootstrap?.preparationPending == true || !lsAttempted ||
                    (lsBootstrap?.isDone == false && lsBootstrap?.stats?.ls?.moves?.sum == 0.0))

        override val stats: SolveStats
            get() {
                observeBootstrapWork()
                val base = if (neighborhood == null) lsBootstrap?.stats ?: bootstrapStats else SolveStats.EMPTY
                return base.copy(alns = telemetry.snapshot())
            }

        override fun runSlice(
            global: Cancellation,
            sliceMillis: Long,
            sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ): MinimizeResult? = runInstructionSlice(
            global, sliceMillis,
            if (sliceNodes < 0L) Long.MAX_VALUE else ceil(sliceNodes * LS_INSTRUCTIONS_PER_WORK).toLong(),
            onIncumbent,
        )

        override fun runInstructionSlice(
            global: Cancellation,
            sliceMillis: Long,
            sliceInstructions: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ): MinimizeResult? {
            verdict?.let { return it }
            check(!closed) { "the ALNS handle is closed" }
            require(sliceInstructions >= 0L) { "slice instructions must be non-negative" }
            val token = if (sliceMillis == Long.MAX_VALUE) {
                global
            } else {
                global or Cancellation.after(sliceMillis.milliseconds)
            }
            if (sliceInstructions == 0L) return null
            // Finite LNS segments are charged their offered scheduling allowance, independently of inner work.
            if (sliceInstructions != Long.MAX_VALUE) charge(sliceInstructions)
            val before = telemetry.bootstrapLsMoves + telemetry.outerAllowance
            try {
                if (token()) return null
                if (neighborhood == null) bootstrapIncumbent(token, sliceInstructions, onIncumbent)
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
                    onIncumbent,
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
            token: Cancellation,
            sliceInstructions: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit,
        ) {
            if (!cpFinished) {
                val cpToken = token.shorten(BT_BOOTSTRAP_FRACTION) or Cancellation { neighborhood != null }
                val started = TimeSource.Monotonic.markNow()
                try {
                    val engine = checkNotNull(backtrack)
                    val bp = (backtrackParams ?: BacktrackParams()).withAssumptions(params.assumptions)
                        .copy(maxDecisions = BOOTSTRAP_DECISIONS, cancellation = cpToken)
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
            val lsToken = token or Cancellation { neighborhood != null }
            val started = TimeSource.Monotonic.markNow()
            try {
                val lp = params.copy(cancellation = lsToken)
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
                neighborhood = NeighborhoodSearch(objective, params, sample, telemetry)
                onIncumbent(checkNotNull(neighborhood).result())
            } else if (search.adopt(sample)) {
                onIncumbent(search.result())
            }
        }

        private fun observeBootstrapWork() {
            cpBootstrap?.let { telemetry.bootstrapCpNodes = it.stats.search.nodes.sum.toLong() }
            lsBootstrap?.let { telemetry.bootstrapLsMoves = it.stats.ls.moves.sum.toLong() }
        }

        private fun charge(instructions: Long) {
            chargedInstructions += minOf(instructions, Long.MAX_VALUE - chargedInstructions)
        }

        private fun finish(result: MinimizeResult): MinimizeResult {
            verdict = result
            close()
            return result
        }

        private fun closeBootstrap() {
            val searches = listOf(cpBootstrap.takeUnless { cpClosed }, lsBootstrap.takeUnless { lsClosed })
            cpClosed = true
            lsClosed = true
            closeSearches(searches)
            observeBootstrapWork()
        }

        private fun closeSearches(searches: List<AutoCloseable?>) {
            var failure: Throwable? = null
            for (search in searches) {
                try {
                    search?.close()
                } catch (closeFailure: Throwable) {
                    val primary = failure
                    if (primary == null) failure = closeFailure else primary.addSuppressed(closeFailure)
                }
            }
            failure?.let { throw it }
        }

        override fun close() {
            if (closed) return
            closed = true
            val searches = listOf(
                cpBootstrap.takeUnless { cpClosed }, lsBootstrap.takeUnless { lsClosed }, neighborhood,
            )
            cpClosed = true
            lsClosed = true
            try {
                closeSearches(searches)
            } finally {
                activeSearch = false
                observeBootstrapWork()
            }
        }
    }

    private fun withTelemetry(result: MinimizeResult, telemetry: AlnsStats): MinimizeResult {
        val stats = result.stats.copy(alns = result.stats.alns.mergedWith(telemetry))
        return when (result) {
            is MinimizeResult.Optimal -> result.copy(stats = stats)
            is MinimizeResult.BestFound -> result.copy(stats = stats)
            is MinimizeResult.Unbounded -> result.copy(stats = stats)
            is MinimizeResult.Infeasible -> result.copy(stats = stats)
            is MinimizeResult.Unknown -> result.copy(stats = stats)
        }
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

    /**
     * Pin every variable *not* in [freed] to its incumbent value, written straight into the sorted
     * primitive arrays [Assumptions] holds — no per-iteration boxing map. The complement is still most
     * of the problem, but the reused repair session re-seeds it by diff
     * ([com.eignex.klause.propagation.PropagationSession.reseedFrom]),
     * so only the pins that actually changed between fragments cost propagation.
     */
    private fun buildPin(problem: Problem, incumbent: Sample, freed: FreedVars): Assumptions {
        val freedBoolSet = IntHashSet().apply { for (b in freed.bools) add(b) }
        val freedIntSet = IntHashSet().apply { for (i in freed.ints) add(i) }
        val boolKeys = IntArray(problem.numBoolVars - freedBoolSet.size)
        val boolValues = BooleanArray(boolKeys.size)
        var bi = 0
        for (b in 0 until problem.numBoolVars) {
            if (b in freedBoolSet) continue
            boolKeys[bi] = b
            boolValues[bi] = incumbent.bools[b]
            bi++
        }
        val intKeys = IntArray(problem.numIntVars - freedIntSet.size)
        val intValues = LongArray(intKeys.size)
        var ii = 0
        for (i in 0 until problem.numIntVars) {
            if (i in freedIntSet) continue
            intKeys[ii] = i
            intValues[ii] = incumbent.ints[i]
            ii++
        }
        // Keys emerge ascending from the 0..n scan — exactly the sorted order the array constructor wants.
        return Assumptions(boolKeys, boolValues, intKeys, intValues)
    }

    private companion object {
        /** Budget slice ([com.eignex.klause.util.Cancellation.shorten]) the complete-engine bootstrap may
         *  use before yielding to destroy/repair. Calibration knob. */
        const val BT_BOOTSTRAP_FRACTION = 0.5

        /** Decision-count safeguard on the complete-engine bootstrap for a deadline-free run, where
         *  `shorten` cannot bound by time. Big enough to reach a first feasible on tight problems. */
        const val BOOTSTRAP_DECISIONS = 50_000L
    }
}
