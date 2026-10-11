@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.localsearch

import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.factor.objective.objectiveSumIsWide
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.GreedyInit
import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundAccumulator
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchStream
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.asSearchStream
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.result.LocalSearchStatsSink
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

/**
 * The local-search engine over a [LocalSearchModel]: strategy, restart cadence and the satisfy and minimize loops.
 * Assignments and payloads belong to each draw; strategy and restart policy belong to the engine.
 * A resumable handle owns the engine exclusively until completion, failure or close.
 *
 * [LocalSearchSolver] is its [com.eignex.klause.solver.Solver] over a baked finite model; a model with an open
 * integer side, which has no baked form, is searched through the engine directly. Local search never refutes a
 * model whose [LocalSearchModel.refutesModel] is false.
 */
internal class LocalSearchEngine(
    val model: LocalSearchModel,
    val strategy: SourceDrivenStrategy = Cbls(),
    val optimizeStrategy: SourceDrivenStrategy? = null,
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    val greedyRepairOnRestart: Boolean = true,
    val definitionalSweep: DefinitionalSweep? = null,
    val perMoveInvariants: Boolean = false,
    val seedImplicitOnRestart: Boolean = false,
    completion: CandidateCompletion? = null,
) {
    private val searchOwned = AtomicBoolean(false)

    private inner class SearchOwnership {
        private var released = false

        fun release() {
            if (released) return
            released = true
            searchOwned.store(false)
        }
    }

    private fun acquireSearch(): SearchOwnership {
        check(searchOwned.compareAndSet(expectedValue = false, newValue = true)) {
            "the local-search solver already has an active search"
        }
        return SearchOwnership()
    }

    private inline fun <T> exclusiveSearch(action: () -> T): T {
        val ownership = acquireSearch()
        try {
            return action()
        } finally {
            ownership.release()
        }
    }

    private fun <T> guarded(sequence: Sequence<T>): Sequence<T> = Sequence {
        val cursor = exclusiveSearch { sequence.iterator() }
        object : Iterator<T> {
            override fun hasNext(): Boolean = exclusiveSearch { cursor.hasNext() }
            override fun next(): T = exclusiveSearch {
                if (!cursor.hasNext()) throw NoSuchElementException()
                cursor.next()
            }
        }
    }

    private val problem: Problem = model.problem
    internal var projection: LocalSearchPreparation = LocalSearchPreparation(problem, model.domains)
    internal var invariantNetwork = InvariantNetworkPreparation(
        definitionalSweep, problem.numIntVars, problem.numBoolVars,
    )

    // Decides each candidate of a model with continuous columns, whose rows are only scored within a tolerance.
    // Null on a model without them, which scores every row exactly and needs no decision.
    private val completion: CandidateCompletion? = completion

    /** Objective-as-constraint ratchet handle (opt-in). Set non-null only for an arm whose [problem]
     *  carries an [com.eignex.klause.factor.objective.ObjectiveBoundFactor] sharing this bound: on each
     *  feasible incumbent the minimize loop tightens it below the incumbent, so the objective slack
     *  re-enters the violation set and the feasibility fight repairs it — the SAT→optimization ratchet
     *  for the violation-native arms (probSAT / WalkSAT). Null leaves objective handling unchanged. */
    var objectiveBound: MutableObjectiveBound? = null

    private val greedyInit: GreedyInit = GreedyInit()

    // The strategy's schedule-axis restart cadence (`ScheduleBundle.restart`) when it declares one,
    // else the solver-level restartPolicy.
    private val configuredRestart: RestartPolicy =
        strategy.schedule.restart ?: restartPolicy

    // When a definitionalSweep is present or seedImplicitOnRestart is set, every restart is followed
    // by implicit feasible-init and/or the sweep plus a recompute, so all restart call sites get the
    // same post-randomization treatment.
    private val restarts: RestartPolicy = if (definitionalSweep == null && !seedImplicitOnRestart) {
        configuredRestart
    } else {
        object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
                configuredRestart.shouldRestart(stepsSinceLastRestart)

            override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) =
                configuredRestart.onLocalOptimum(state, sample, objective)

            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                configuredRestart.restart(state, bestSoFar)
                prepareAssignment(state)
                state.recompute()
            }
        }
    }

    private fun prepareAssignment(state: LocalSearchState) {
        if (seedImplicitOnRestart) state.seedImplicitFeasible()
        definitionalSweep?.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
    }

    private suspend fun <T> SequenceScope<T?>.initialRestart(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        // These reset policies start from an unanchored random draw; scoring before the sweep is redundant.
        val randomDraw = configuredRestart is FixedCadenceRestart || configuredRestart is AdaptivePerturbationRestart ||
            configuredRestart is IteratedLocalSearchRestart || configuredRestart is StagnationRestart ||
            configuredRestart is LubyRestart
        if (!randomDraw || (!slicedInitialization(checkpoint) && definitionalSweep == null && !seedImplicitOnRestart)) {
            restarts.restart(state, bestSoFar = null)
            return
        }
        prepareSteps(state.initialAssignmentSteps(null), params, checkpoint)
        prepareInitialAssignment(state, params, checkpoint)
        recomputeInitial(state, params, checkpoint)
        (configuredRestart as? LubyRestart)?.advanceCadence()
    }

    private fun slicedInitialization(checkpoint: LocalSearchCheckpoint?): Boolean =
        checkpoint != null

    private suspend fun <T> SequenceScope<T?>.recomputeInitial(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        if (!slicedInitialization(checkpoint)) {
            state.recompute()
            return
        }
        // Partial degrees and payloads stay private to the suspended stream until every factor is initialized.
        for (from in 0 until problem.numFactors step INITIAL_FACTOR_BATCH) {
            while (params.cancellation()) yield(null)
            state.initializeFactors(from, minOf(from + INITIAL_FACTOR_BATCH, problem.numFactors))
        }
        state.finishRecompute()
    }

    private suspend fun <T> SequenceScope<T?>.preparedProjection(
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ): LocalSearchProblem {
        if (checkpoint == null) return checkNotNull(projection.get(Cancellation.Never))
        while (true) {
            projection.get(params.cancellation)?.let { return it }
            yield(null)
        }
    }

    private suspend fun <T> SequenceScope<T?>.installInvariants(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        if (!perMoveInvariants) return
        val cancellation = if (checkpoint == null) Cancellation.Never else params.cancellation
        while (!invariantNetwork.get(cancellation)) yield(null)
        state.invariants = invariantNetwork.value
    }

    private suspend fun <T> SequenceScope<T?>.prepareSteps(
        steps: Sequence<Unit>,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        val cursor = steps.iterator()
        while (true) {
            while (checkpoint != null && params.cancellation()) yield(null)
            if (!cursor.hasNext()) return
            cursor.next()
        }
    }

    private suspend fun <T, R : Any> SequenceScope<T?>.preparedValue(
        steps: Sequence<R?>,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ): R {
        val cursor = steps.iterator()
        while (true) {
            while (checkpoint != null && params.cancellation()) yield(null)
            cursor.next()?.let { return it }
        }
    }

    private suspend fun <T> SequenceScope<T?>.prepareInitialAssignment(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
    ) {
        if (seedImplicitOnRestart) prepareSteps(state.seeding.seedSteps(state), params, checkpoint)
        definitionalSweep?.let {
            prepareSteps(
                it.pinnedSteps(state.assignment, state.rootDomains, problem.factors, state.assumptions),
                params, checkpoint,
            )
        }
    }

    private suspend fun <T> SequenceScope<T?>.newState(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        seed: Long,
        checkpoint: LocalSearchCheckpoint?,
        timing: LocalSearchPhaseTimer?,
    ): LocalSearchState {
        val prepared = preparedProjection(params, checkpoint)
        val state = preparedValue(
            LocalSearchState.preparation(model, Random(seed), effectiveAssumptions, prepared), params, checkpoint,
        )
        state.phaseTimer = timing
        state.violationSoftCap = params.violationSoftCap
        state.weights.normalizeWeightsByClass = params.normalizeWeightsByClass
        installInvariants(state, params, checkpoint)
        return state
    }

    private suspend fun <T> SequenceScope<T?>.seedInitial(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
        reconcile: Boolean,
    ): Boolean {
        val sample = params.initialAssignment ?: return false
        if (sample.numBoolVars != problem.numBoolVars || sample.numIntVars != problem.numIntVars) return false
        if (checkpoint == null) {
            seedFrom(state, sample)
            if (reconcile) definitionalSweep?.let {
                it.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
                state.recompute()
            }
        } else {
            prepareSteps(state.initialAssignmentSteps(sample), params, checkpoint)
            if (reconcile) definitionalSweep?.let {
                prepareSteps(
                    it.pinnedSteps(state.assignment, state.rootDomains, problem.factors, state.assumptions),
                    params, checkpoint,
                )
            }
            recomputeInitial(state, params, checkpoint)
        }
        return true
    }

    private fun phaseTimer(params: LocalSearchParams, sink: SolveStatsSink?): LocalSearchPhaseTimer? {
        if (!params.reportPhaseTimes || sink == null) return null
        return LocalSearchPhaseTimer().also { sink.ls.phaseNanos = it::snapshot }
    }

    /** A one-line summary of this engine's configuration under [params]. */
    fun describe(params: LocalSearchParams): String {
        val sources = strategy.sources.joinToString(",") { it.source.id.label }
        return """
            local-search
              sources:    [$sources]
              scoring:    ${strategy.scoring}
              acceptance: ${strategy.acceptance}
              restart:    ${restartPolicy::class.simpleName}
              max-flips:  ${params.maxFlips}
        """.trimIndent()
    }

    /** Search once for a solution, syncing learned weights through [warm] when it is non-null. */
    fun solve(params: LocalSearchParams, warm: WarmState?): SolveResult = exclusiveSearch {
        solveExclusive(params, warm)
    }

    private fun solveExclusive(params: LocalSearchParams, warm: WarmState?): SolveResult {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        if (!localSearchSupports(model, completion != null)) {
            // LP-only continuous variables are resolved by the LP relaxation, which local search does not
            // run; their linear rows carry no invariant, so LS would ignore them and could report a
            // solution that violates them. Domain values past the 32-bit range make the incremental
            // violation/objective sums wrap, so a reported "solution" may violate factors too. A wide
            // (over-64-bit-coefficient) factor carries only a saturated Long payload, so its invariant is
            // inert and LS would ignore it — the same unsoundness. Decline rather than return an unsound verdict.
            sink.stop()
            return SolveResult.Unknown(TerminationReason.Unsupported, sink.snapshot())
        }
        val eff = model.pinsUnder(params.assumptions)
        if (eff == null) {
            sink.stop()
            return if (model.refutesModel) {
                SolveResult.Unsat(stats = sink.snapshot())
            } else {
                // A root refuted inside windows the engine chose covers none of the model: nothing was exhausted.
                SolveResult.Unknown(TerminationReason.Unsupported, sink.snapshot())
            }
        }
        val sample = streamImpl(params, eff, warm, sink).filterNotNull().firstOrNull()
        sink.stop()
        return if (sample != null) {
            SolveResult.Sat(sample, sink.snapshot())
        } else {
            sink.timedOut = true
            SolveResult.Unknown(TerminationReason.BudgetExhausted, sink.snapshot())
        }
    }

    /** Stream independent solutions, syncing learned weights through [warm] when it is non-null. */
    fun samples(params: LocalSearchParams, warm: WarmState?): Sequence<Sample> {
        // LP-only continuous variables, wide int domains, and wide-coefficient factors are not soundly
        // evaluated by local search (see [solve]); stream nothing rather than assignments that
        // may violate factors.
        if (!localSearchSupports(model, completion != null)) return emptySequence()
        val eff = model.pinsUnder(params.assumptions) ?: return emptySequence()
        return guarded(streamImpl(params, eff, warm).filterNotNull())
    }

    fun openSamples(
        params: LocalSearchParams,
        warm: WarmState?,
        transform: (Sequence<Sample>) -> Sequence<Sample> = { it },
    ): SearchStream<Sample> {
        val ownership = acquireSearch()
        return runCatching {
            val supported = localSearchSupports(model, completion != null)
            val effective = if (supported) model.pinsUnder(params.assumptions) else null
            val sequence = if (effective == null) emptySequence()
            else streamImpl(params, effective, warm).filterNotNull()
            transform(sequence).asSearchStream(release = ownership::release)
        }.getOrElse {
            ownership.release()
            throw it
        }
    }

    fun resumableSolve(params: LocalSearchParams, warm: WarmState? = null): ResumableSolve {
        val ownership = acquireSearch()
        return runCatching { openResumableSolve(params, warm, ownership) }.getOrElse {
            ownership.release()
            throw it
        }
    }

    private fun openResumableSolve(
        params: LocalSearchParams,
        warm: WarmState?,
        ownership: SearchOwnership,
    ): ResumableSolve {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        val supported = localSearchSupports(model, completion != null)
        val effective = if (supported) model.pinsUnder(params.assumptions) else null
        val maxInstructions = moveCap(params)
        val control = LocalSearchRunControl(params.cancellation)
        val cursor = effective?.let {
            streamImpl(
                params.copy(cancellation = control.cancellation),
                it,
                warm = warm,
                sink = sink,
                checkpoint = control,
            ).iterator()
        }
        return LocalSearchSolveRun(
            params, supported, effective != null, model.refutesModel, maxInstructions,
            cursor, sink, control, ownership::release,
        )
    }

    /**
     * Streaming best-effort minimisation of [objective] under the hard constraints. Yields one
     * [MinimizeResult.BestFound] per new incumbent established during the inner loop (i.e. every time
     * `obj < bestObj` strictly improves), followed by exactly one terminal verdict.
     *
     * Local search is **incomplete**: it never proves optimality or infeasibility. The terminal verdict is
     * [MinimizeResult.Infeasible] only when root propagation refutes a model whose domains it declared, before
     * any LS work happens; otherwise either a final [MinimizeResult.BestFound] (carrying the same sample as the
     * last intermediate yield, with the real termination reason) or [MinimizeResult.Unknown] when LS never
     * reached feasibility.
     *
     * Reaches feasibility via the configured [strategy], then descends on the objective per its
     * [SourceDrivenStrategy.feasibleDescent]. Scores moves by the O(arity) incremental [LinearObjective] delta,
     * or by [LocalSearchParams.lsObjective]'s `deltaIfApplied` when the caller supplies that gradient view of
     * the same objective (the functionally-defined-cone case).
     */
    fun improvements(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
    ): Sequence<MinimizeResult> = guarded(
        minimizeStream(objective, params, warm, SolveStatsSink(backend = "ls")).filterNotNull(),
    )

    fun openImprovements(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
    ): SearchStream<MinimizeResult> {
        val ownership = acquireSearch()
        val sink = SolveStatsSink(backend = "ls")
        var finished = false
        return minimizeStream(objective, params, warm, sink, onFinished = { finished = true })
            .filterNotNull().asSearchStream(
                release = { try { if (!finished) sink.stop() } finally { ownership.release() } },
                terminal = { finished },
            )
    }

    @Suppress("LongParameterList")
    private fun minimizeStream(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
        sink: SolveStatsSink,
        checkpoint: LocalSearchCheckpoint? = null,
        onFinished: () -> Unit = {},
        timing: LocalSearchPhaseTimer? = phaseTimer(params, sink),
    ): Sequence<MinimizeResult?> = sequence {
        sink.start()
        if (!localSearchSupports(model, completion != null)) {
            // Same soundness boundary as [solve]: LP-only continuous variables, wide int
            // domains, and wide-coefficient factors are not evaluated by local search, so it could
            // optimize an incumbent that ignores — and may violate — them. Decline.
            sink.stop()
            onFinished()
            yield(MinimizeResult.Unknown(TerminationReason.Unsupported, sink.snapshot()))
            return@sequence
        }
        val eff = model.pinsUnder(params.assumptions)
        if (eff == null) {
            sink.stop()
            onFinished()
            yield(
                if (model.refutesModel) {
                    MinimizeResult.Infeasible(stats = sink.snapshot())
                } else {
                    // Not SearchExhausted, which a portfolio reads as the whole space covered.
                    MinimizeResult.Unknown(TerminationReason.Unsupported, sink.snapshot())
                },
            )
            return@sequence
        }
        // The caller's gradient view reads the objective's defined variables off their definitions, so it
        // agrees with the linear objective only while per-move invariants hold them there. Without them a
        // defined variable is searched like any other and can sit wherever the model lets it, and the view
        // would score an incumbent better than its own assignment. Even with them, presolve may have
        // eliminated a definition the view reads, so the view only guides moves: every incumbent is valued
        // by the linear objective, the one the portfolio checks it against.
        val gradient = params.lsObjective?.takeIf { perMoveInvariants && definitionalSweep != null }
        runMinimizeStream(gradient ?: objective, objective, params, eff, warm, sink, checkpoint, onFinished, timing)
    }.withActiveLocalSearchTime(timing)

    fun resumable(objective: LinearObjective, params: LocalSearchParams, warm: WarmState? = null): ResumableSearch {
        val ownership = acquireSearch()
        return runCatching { openResumable(objective, params, warm, ownership) }.getOrElse {
            ownership.release()
            throw it
        }
    }

    private fun openResumable(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
        ownership: SearchOwnership,
    ): ResumableSearch {
        val sink = SolveStatsSink(backend = "ls")
        val control = LocalSearchRunControl(params.cancellation)
        val cursor = minimizeStream(
            objective,
            params.copy(cancellation = control.cancellation),
            warm,
            sink,
            control,
            onFinished = control::finishStream,
        ).iterator()
        return LocalSearchOptimizeRun(params, cursor, sink, control, ownership::release)
    }

    // The moves one run may make: its own caps, and what is left of the solve's node budget.
    private fun moveCap(params: LocalSearchParams): Long = minOf(
        params.maxFlips,
        params.maxInstructions ?: Long.MAX_VALUE,
        params.nodeBudget?.movesLeft() ?: Long.MAX_VALUE,
    )

    private fun streamImpl(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState? = null,
        sink: SolveStatsSink? = null,
        checkpoint: LocalSearchCheckpoint? = null,
    ): Sequence<Sample?> {
        val timing = phaseTimer(params, sink)
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val maxFlips = moveCap(params)
        return sequence {
            val state = newSatisfyState(params, effectiveAssumptions, warm, seed, checkpoint, timing)
            val progress = LocalSearchProgress(state, params, warm, sink)
            var flipsSinceRestart = 0
            var bestCost = state.cost
            // Only the final built-in random policies ignore anchors; custom policies retain their protocol.
            val bestAnchor = if (configuredRestart is FixedCadenceRestart || configuredRestart is LubyRestart) {
                null
            } else {
                preparedValue(
                    Assignment.preparation(problem.numBoolVars, problem.numIntVars, problem.numRealVars),
                    params, checkpoint,
                ).also {
                    prepareSteps(state.assignment.copySteps(it, INITIALIZATION_BATCH), params, checkpoint)
                }
            }
            // Bounded per yield, not per session: maxFlips elapsing without a fresh sample means the
            // search neighbourhood is effectively exhausted, so end the sequence.
            var flipsSinceYield = 0L
            var cancelCountdown = 0
            var moves = 0L
            var restartCount = 0L
            var everFeasible = false
            // Use the unwrapped restart policy so an adaptive one is detected past a sweep wrapper.
            val roundFeedback = RoundFeedback.of(strategy, configuredRestart)

            fun reportProgress() = progress.satisfaction(moves, restartCount, bestCost, everFeasible)

            // A restart transition consumes one unit of the maxFlips/maxInstructions allowance, same as
            // an applied move (see [LocalSearchParams.maxInstructions]).
            fun countedRestart(useAnchor: Boolean = true) {
                val anchor = if (useAnchor) bestAnchor?.snapshot() else null
                timing.inPhase("seeding") { restarts.restart(state, bestSoFar = anchor) }
                moves++
                flipsSinceYield++
                restartCount++
                flipsSinceRestart = 0
            }

            try {
                while (flipsSinceYield < maxFlips) {
                    progress.observeResidual()
                    if (cancelCountdown-- <= 0) {
                        while (params.cancellation()) {
                            if (checkpoint == null) return@sequence
                            reportProgress()
                            yield(null)
                        }
                        cancelCountdown = CANCEL_CHECK_INTERVAL
                    }
                    if (state.cost == 0L && state.intValuesInDomain()) {
                        if (completion != null) state.refreshRealRows()
                        if (state.cost != 0L) continue
                        val decided = decide(
                            state,
                            state.assignment.snapshot(),
                            params.cancellation,
                            sink?.ls,
                        ) { work ->
                            moves += work
                            flipsSinceYield += work
                        }
                        if (decided !is Completion.Witness) {
                            if (checkpoint != null && decided is Completion.Undecided && params.cancellation()) {
                                checkpoint.shouldPause(moves)
                                reportProgress()
                                yield(null)
                                continue
                            }
                            countedRestart()
                            if (checkpoint?.shouldPause(moves) == true) {
                                reportProgress()
                                yield(null)
                            }
                            continue
                        }
                        val solution = decided.sample
                        if (!everFeasible) {
                            everFeasible = true
                            progress.publishResidual()
                            // Record at first feasibility, not in `finally`: the `firstOrNull` consumer
                            // suspends this coroutine at the `yield` below and never resumes it, so
                            // `finally` would not fire on the success path.
                            sink?.ls?.recordWork(moves = moves, restarts = restartCount, stalls = 0L)
                            sink?.ls?.recordIncumbent(
                                objective = Double.NaN,
                                violation = 0.0,
                                foundAtMs = sink.elapsedMs(),
                            )
                        }
                        // Sync warm state on every yield so streaming consumers that never drain the
                        // sequence still see captured weights.
                        progress.captureWarm()
                        progress.charge(moves)
                        checkpoint?.shouldPause(moves)
                        yield(solution)
                        flipsSinceYield = 0
                        countedRestart(useAnchor = false)
                        bestCost = state.cost
                        bestAnchor?.let { state.assignment.copyInto(it) }
                        continue
                    }
                    if (restarts.shouldRestart(flipsSinceRestart)) {
                        countedRestart()
                        roundFeedback?.endRound()
                        if (checkpoint?.shouldPause(moves) == true) {
                            reportProgress()
                            yield(null)
                        }
                        continue
                    }
                    val costBefore = state.cost
                    val move = timing.inPhase("selection") { strategy.pickMove(state) }
                    if (move == null) {
                        countedRestart()
                        roundFeedback?.endRound()
                        if (checkpoint?.shouldPause(moves) == true) {
                            reportProgress()
                            yield(null)
                        }
                        continue
                    }
                    timing.inPhase("application") { state.apply(move) }
                    moves++
                    if (state.cost < bestCost) {
                        bestCost = state.cost
                        bestAnchor?.let { state.assignment.copyInto(it) }
                    }
                    flipsSinceRestart++
                    flipsSinceYield++
                    roundFeedback?.record(costBefore, state.cost, moves)
                    if (checkpoint?.shouldPause(moves) == true) {
                        reportProgress()
                        yield(null)
                    }
                }
            } finally {
                // Sync learned weights back into warm state on natural exit or consumer cancel.
                // Abandoned sequences may not fire this; accepted loss.
                reportProgress()
            }
        }.withActiveLocalSearchTime(timing)
    }

    /**
     * Streaming body of the LS minimize loop. Yields a [MinimizeResult.BestFound] on
     * every strict improvement; yields exactly one terminal verdict
     * ([MinimizeResult.BestFound] with reason, or [MinimizeResult.Unknown]) on exit.
     * Two-phase per restart attempt: WalkSat-style fight to feasibility, then a greedy
     * descent on the objective restricted to feasibility-preserving moves. When the
     * descent reaches a local minimum (no neighbour both keeps `cost == 0` and lowers
     * the objective), restart and try again. Best-feasible-objective state lives across
     * restarts so we monotonically improve.
     */
    // [guide] scores moves; [objective] values every incumbent this stream reports.
    @Suppress("LongParameterList")
    private suspend fun SequenceScope<MinimizeResult?>.runMinimizeStream(
        guide: Objective,
        objective: Objective,
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
        sink: SolveStatsSink,
        checkpoint: LocalSearchCheckpoint? = null,
        onFinished: () -> Unit = {},
        timing: LocalSearchPhaseTimer? = null,
    ) {
        val state = newMinimizeState(guide, params, effectiveAssumptions, warm, checkpoint, timing)
        val progress = LocalSearchProgress(state, params, warm, sink)
        // An objective whose sum can pass the 64-bit range is scored from snapshots, which sum it exactly; the live
        // assignment's Long evaluation would wrap.
        val wideObjective = objective is LinearObjective && objective.isWideOver(state.rootDomains)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        var bestCostInfeasible: Long = Long.MAX_VALUE
        var flipsSinceRestart = 0
        var totalFlips = 0L
        var restartCount = 0L
        var stallCount = 0L
        var bestFoundAtMs = -1L
        val maxFlips = moveCap(params)
        var cancelled = false

        // Cross-engine solution flow: publish each improvement into the shared exchange, and before a
        // restart adopt a fresher-and-better published assignment as the incumbent so the restart anchors
        // on a peer arm's solution. Purely heuristic in that direction — the anchor only seeds the next
        // descent — and skipped under assumption pins a foreign assignment may violate.
        val pooled = PooledIncumbents(
            exchange = params.pooledIncumbents,
            importEnabled = effectiveAssumptions.isEmpty,
            evaluate = { objective.evaluate(it) },
        )

        // Restart policies require a feasible anchor; residual violation cannot certify one.
        fun restartAnchor(): Sample? {
            pooled.poll(bestObj)?.let { (sample, obj) ->
                bestObj = obj
                bestSample = sample
            }
            return bestSample
        }

        // Each restart counts as one unit of work against maxFlips; otherwise a degenerate objective
        // on a constraint-free problem would loop forever (cost stays 0, descent never improves, and
        // the restart path wouldn't bump totalFlips).
        // Phase strategy: an optimizeStrategy (when set) drives both phases; else the satisfy strategy
        // drives, and the optimize phase follows the strategy's own [FeasibleDescent] mode.
        val descentStrategy = optimizeStrategy
        val unified = descentStrategy != null
        // Capture the ratchet handle once: set at construction, read in the feasible-incumbent hook.
        val ratchetBound = objectiveBound
        // The explicit feasible-phase descent mode — the optimize strategy's when present, else the
        // satisfy strategy's. The cost==0 branch dispatches on it exhaustively (no fall-through), so an
        // arm always optimizes the way it declared and never lands in a default descent by accident.
        val feasibleMode = (descentStrategy ?: strategy).feasibleDescent
        // Sampling-miss tolerance for a SelfOwned feasible walk: how many consecutive null picks to
        // re-sample before a restart (see [SourceDrivenStrategy.feasibleResampleCap]).
        val feasibleResampleCap = (descentStrategy ?: strategy).feasibleResampleCap
        var feasibleMisses = 0
        // The feasibility-fight strategy whose moves form the rounds (the unified descent strategy
        // when one drives both phases, else the satisfy strategy).
        val satisfyStrategy: SourceDrivenStrategy = if (unified) descentStrategy else strategy
        // Feed round stats to the unwrapped restart policy so an adaptive one is detected even when a
        // definitional-sweep wrapper stands in for shouldRestart/restart.
        val roundFeedback = RoundFeedback.of(satisfyStrategy, configuredRestart)
        var cancelCountdown = 0
        var lastCheckMs = 0L

        fun reportProgress() = progress.optimization(
            totalFlips, restartCount, stallCount, bestObj, bestSample != null,
            bestCostInfeasible, bestFoundAtMs, checkpoint,
        )

        while (totalFlips < maxFlips) {
            progress.observeResidual()
            while (checkpoint?.shouldPause(totalFlips) == true) {
                reportProgress()
                yield(null)
                cancelCountdown = 0
            }
            if (params.nodeBudget?.movesLeft() == 0L) break
            if (cancelCountdown-- <= 0) {
                while (params.cancellation()) {
                    if (checkpoint != null) {
                        reportProgress()
                        yield(null)
                        continue
                    }
                    cancelled = true
                    break
                }
                if (cancelled) break
                // Auto-tune the next poll window to ~[CANCEL_CHECK_TARGET_MS] of wall-clock: cheap flips
                // keep the full interval (negligible overhead), while expensive move sources
                // (flip-propagate / clique-swap / ejection chains) shrink it, so a slow flip window can't
                // overrun the `-t` deadline by more than that margin.
                val nowMs = sink.elapsedMs()
                val gapMs = maxOf(nowMs - lastCheckMs, 1L)
                cancelCountdown = (CANCEL_CHECK_INTERVAL.toLong() * CANCEL_CHECK_TARGET_MS / gapMs)
                    .coerceIn(1L, CANCEL_CHECK_INTERVAL.toLong()).toInt()
                lastCheckMs = nowMs
            } else if ((cancelCountdown and (CANCEL_CLOCK_STRIDE - 1)) == 0) {
                // Mid-window escape hatch: the window is tuned on the *previous* window's
                // cost, so a step mix that suddenly turns expensive — an ultra-wide row entering the
                // violated repair pool — would otherwise grind out up to a full window of monster
                // steps before the next poll. A clock glance every [CANCEL_CLOCK_STRIDE] steps costs
                // nothing on the fast path and forces the poll once the window blows its budget.
                if (sink.elapsedMs() - lastCheckMs >= CANCEL_CHECK_TARGET_MS * CANCEL_OVERRUN_FACTOR) {
                    cancelCountdown = 0
                }
            }
            if (state.cost == 0L) {
                // Each descent step is O(numVars); the once-per-CANCEL_CHECK_INTERVAL throttle above
                // is too coarse to keep the optimize phase deadline-responsive. One extra poll per
                // descent step is negligible against the step's own cost.
                while (params.cancellation()) {
                    if (checkpoint != null) {
                        reportProgress()
                        yield(null)
                        continue
                    }
                    cancelled = true
                    break
                }
                if (cancelled) break
                // Score the live assignment without copying it; the snapshot is taken only on a strict
                // improvement, so the steady state allocates nothing per iteration.
                if (completion != null) {
                    state.refreshRealRows()
                    if (state.cost != 0L) {
                        totalFlips++
                        continue
                    }
                }
                val obj = if (completion != null || wideObjective) {
                    objective.evaluate(state.assignment.snapshot())
                } else {
                    objective.evaluate(state.assignment)
                }
                if (obj < bestObj && state.intValuesInDomain()) {
                    val decided = decide(
                        state,
                        state.assignment.snapshot(),
                        params.cancellation,
                        sink.ls,
                    ) { work -> totalFlips += work }
                    if (decided !is Completion.Witness) {
                        if (checkpoint != null && decided is Completion.Undecided && params.cancellation()) {
                            reportProgress()
                            yield(null)
                            cancelCountdown = 0
                            continue
                        }
                        restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                    val solution = decided.sample
                    if (completion != null) adoptReals(state, solution)
                    val solved = if (completion != null || wideObjective) objective.evaluate(solution) else obj
                    if (solved < bestObj) {
                        bestObj = solved
                        bestSample = solution
                        bestFoundAtMs = sink.elapsedMs()
                        params.onEvent?.invoke(SearchEvent.Incumbent(solved))
                        pooled.publish(solution, solved)
                        reportProgress()
                        yield(MinimizeResult.BestFound(solution, solved, TerminationReason.BudgetExhausted))
                        cancelCountdown = 0
                        continue
                    }
                }
                // Explicit feasible-phase dispatch — exhaustive, no else: every strategy declares its
                // [FeasibleDescent], so nothing falls into a default descent by accident.
                when (feasibleMode) {
                    // Violation-native: the objective is an `objective ≤ incumbent` factor the portfolio
                    // overlaid on a COP. Reaching cost==0 means it already holds — tighten the bound below
                    // this incumbent so "beat it" re-enters the violation set, reconcile just the bound
                    // factor (its degree shifts with no move; the overlay appends it last), and drop back
                    // into the feasibility fight. Surgical, not a full recompute. With no bound (a var-less
                    // objective) there is nothing to optimize, so restart to diversify.
                    FeasibleDescent.RatchetAsConstraint -> {
                        if (ratchetBound != null) {
                            ratchetBound.tightenBelow(obj)
                            state.reevaluateFactor(problem.numFactors - 1)
                            totalFlips++
                            continue
                        }
                        restarts.onLocalOptimum(state, state.assignment.snapshot(), obj)
                        restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                        stallCount++
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }

                    // The strategy's own pickMove (its sources + acceptance) owns the feasible walk: the
                    // engine commits whatever feasibility-preserving move it picks — CBLS descends greedily
                    // on its objective / structured / pair-swap sources, SA anneals through worse-objective
                    // states. Best-feasible is snapshotted above, so wandering never loses the incumbent. A
                    // feasibility-breaking pick is reverted and retried; a null pick is a local optimum.
                    FeasibleDescent.SelfOwned -> {
                        val m = timing.inPhase("selection") { (descentStrategy ?: strategy).pickMove(state) }
                        if (m != null) {
                            feasibleMisses = 0
                            val savedSnap = state.assignment.snapshot()
                            timing.inPhase("application") {
                                state.apply(m)
                                if (state.cost != 0L) revertMove(state, m, savedSnap)
                            }
                            flipsSinceRestart++
                            totalFlips++
                            continue
                        }
                        // No move this draw. For a sampled strategy that is usually an unlucky draw rather
                        // than a true local optimum, so re-sample (and let the stall machinery engage) up to
                        // feasibleResampleCap times before diversifying — a restart here discards the current
                        // feasible solution. cap == 0 restarts immediately (exhaustive-generation semantics).
                        if (feasibleMisses < feasibleResampleCap) {
                            feasibleMisses++
                            flipsSinceRestart++
                            totalFlips++
                            continue
                        }
                        feasibleMisses = 0
                        restarts.onLocalOptimum(state, state.assignment.snapshot(), obj)
                        restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                        stallCount++
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                }
            }
            if (restarts.shouldRestart(flipsSinceRestart)) {
                restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                roundFeedback?.endRound()
                continue
            }
            // Pre-feasibility: drive through the unified strategy when one is configured, else the
            // satisfy-mode strategy.
            val costBefore = state.cost
            val move = timing.inPhase("selection") {
                if (unified) descentStrategy.pickMove(state) else strategy.pickMove(state)
            }
            if (move == null) {
                restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                roundFeedback?.endRound() // a restart ends the round; don't span it
                continue
            }
            timing.inPhase("application") { state.apply(move) }
            if (state.cost in 1 until bestCostInfeasible) {
                bestCostInfeasible = state.cost
            }
            flipsSinceRestart++
            totalFlips++
            roundFeedback?.record(costBefore, state.cost, totalFlips)
        }
        reportProgress()
        val reason = if (cancelled) TerminationReason.Cancelled else TerminationReason.BudgetExhausted
        sink.stop()
        sink.timedOut = reason == TerminationReason.BudgetExhausted
        sink.ls.recordWork(moves = totalFlips, restarts = restartCount, stalls = stallCount)
        progress.charge(totalFlips)
        // Feasible incumbent → violation 0 at bestObj; else carry the lowest residual cost reached.
        // Long.MAX_VALUE means we never improved on the initial assignment, so leave violation NaN.
        if (bestSample != null) {
            sink.ls.recordIncumbent(objective = bestObj, violation = 0.0, foundAtMs = bestFoundAtMs)
        } else if (bestCostInfeasible != Long.MAX_VALUE) {
            sink.ls.recordIncumbent(
                objective = Double.NaN,
                violation = bestCostInfeasible.toDouble(),
                foundAtMs = -1L,
            )
        }
        onFinished()
        yield(
            if (bestSample != null) {
                MinimizeResult.BestFound(bestSample, bestObj, reason, sink.snapshot())
            } else {
                MinimizeResult.Unknown(reason, sink.snapshot())
            },
        )
    }

    /** Build and prime the per-call [LocalSearchState] for a [runMinimizeStream] draw: config the
     *  violation cap / weight normalisation, install invariants and warm state, plumb the objective and
     *  its shaping lambda, then reach a start pose — warm-seed (with a definitional reconcile) when the
     *  caller supplied [LocalSearchParams.initialAssignment], else a random restart followed by the
     *  size-gated greedy repair. Ends where the loop's own bookkeeping begins. */
    private suspend fun <T> SequenceScope<T?>.newMinimizeState(
        objective: Objective,
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
        checkpoint: LocalSearchCheckpoint?,
        timing: LocalSearchPhaseTimer? = null,
    ): LocalSearchState = timing.inPhase("seeding") {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = timing.inPhase("preparation") {
            newState(params, effectiveAssumptions, seed, checkpoint, timing)
        }
        warm?.let { prepareSteps(it.applySteps(state), params, checkpoint) }
        // Mirror newSatisfyState: a restart policy instance can be reused across solves (one tuning
        // recipe over many problems), so clear its per-solve state before any restart here — else a
        // stale incumbent of a different variable arity is anchored/perturbed against this problem.
        configuredRestart.reset()
        // Plumb shaping into the state so strategies consulting shapedBreakScore see the objective
        // during pre-feasibility moves. Only CostShaping.Linear contributes a non-zero lambda;
        // FeasibilityFirst leaves it at 0.0, identical to the no-shaping path.
        state.shaping.objective = objective
        state.shaping.shapingLambda = (params.costShaping as? CostShaping.Linear)?.lambda ?: 0.0
        // Warm-start from a caller-supplied (arity-compatible) assignment instead of a random
        // restart; null by default. See [LocalSearchParams.initialAssignment].
        val seeded = seedInitial(state, params, checkpoint, reconcile = true)
        if (!seeded) initialRestart(state, params, checkpoint)
        // Greedy-repair is gated on problem size: on tiny problems LS reaches feasibility in
        // microseconds and the repair pass is pure overhead. Skip it on a warm start: the seed is
        // already feasible, and the repair sweep is objective-blind (it accepts any flip that doesn't
        // raise cost), so on a cost-0 seed it would wander across equal-cost feasibles and discard the
        // seed's objective — defeating the warm start.
        if (greedyRepairOnRestart && isLargeEnoughForGreedy() && !seeded) greedyRepairPass(state, params, checkpoint)
        state
    }

    /** Build and prime the per-call [LocalSearchState] for a [streamImpl] satisfy draw: config the
     *  violation cap / weight normalisation, install invariants and warm state, reset the (possibly
     *  reused) restart policy's per-solve state, then take the first random restart. */
    private suspend fun <T> SequenceScope<T?>.newSatisfyState(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
        seed: Long,
        checkpoint: LocalSearchCheckpoint?,
        timing: LocalSearchPhaseTimer? = null,
    ): LocalSearchState = timing.inPhase("seeding") {
        val state = timing.inPhase("preparation") {
            newState(params, effectiveAssumptions, seed, checkpoint, timing)
        }
        warm?.let { prepareSteps(it.applySteps(state), params, checkpoint) }
        // A restart policy instance can be reused across solves (a tuning campaign runs one
        // recipe over many problems); clear its per-solve state so a stale incumbent — possibly
        // of a different variable arity — can't leak in and be indexed against this problem.
        // ScheduleBundle leaves restart reset to the engine; reset the underlying policy, not
        // the `restarts` wrapper (whose reset is the interface no-op).
        configuredRestart.reset()
        // A caller-supplied starting point stands in for the first random restart; later restarts are the policy's.
        val seeded = seedInitial(state, params, checkpoint, reconcile = false)
        if (!seeded) initialRestart(state, params, checkpoint)
        state
    }

    // Zero violation over continuous columns needs completion; refuting rows gain weight to steer the search.
    private fun decide(
        state: LocalSearchState,
        candidate: Sample,
        cancellation: Cancellation,
        stats: LocalSearchStatsSink?,
        charge: (Long) -> Unit,
    ): Completion {
        val completion = completion ?: return Completion.Witness(candidate)
        val decided = completion.complete(candidate, cancellation)
        charge(decided.work)
        stats?.recordCompletion(refuted = decided is Completion.Refuted, undecided = decided is Completion.Undecided)
        if (decided is Completion.Refuted) {
            val weights = state.weights.factorWeights
            for (f in decided.factors) weights[f] += 1.0
        }
        return decided
    }

    /** Move [state]'s continuous columns onto [solution]'s completed values, so the search goes on from the exact
     *  point the completion found rather than its own floating-point guess. */
    private fun adoptReals(state: LocalSearchState, solution: Sample) {
        for (r in 0 until problem.numRealVars) {
            val value = solution.approximateRealValue(r)
            if (value.toRawBits() != state.assignment.realValue(r).toRawBits()) state.apply(Move.RealSet(r, value))
        }
    }

    /** Restart [state] from [anchor] and re-run the greedy repair sweep under the same size gate as
     *  the initial restart — the pairing every minimize restart site must preserve. */
    private suspend fun SequenceScope<MinimizeResult?>.restartAndRepair(
        state: LocalSearchState,
        anchor: Sample?,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
        onPause: () -> Unit,
    ) = state.phaseTimer.inPhase("seeding") {
        restarts.restart(state, anchor)
        if (greedyRepairOnRestart && isLargeEnoughForGreedy()) greedyRepairPass(state, params, checkpoint, onPause)
    }

    /** True when the problem is big enough that the post-restart greedy-repair sweep pays for
     *  itself; tiny problems reach feasibility in microseconds and the sweep is pure overhead. */
    private fun isLargeEnoughForGreedy(): Boolean = (problem.numBoolVars + problem.numIntVars) >= 32

    /** Load [sample] into [state]'s assignment (re-pinning assumed slots), reset the tabu/CC
     *  epoch, and recompute cost/degrees — the warm-start seed path for
     *  [LocalSearchParams.initialAssignment]. Returns false (leaving the state untouched for a
     *  normal random restart) when the sample's arity doesn't match this problem. */
    private fun seedFrom(state: LocalSearchState, sample: Sample, recompute: Boolean = true): Boolean {
        if (sample.numBoolVars != problem.numBoolVars || sample.numIntVars != problem.numIntVars) return false
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.boolValue(b))
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.intValue(i))
        // Respect caller pins as restart() does, so a seed can't violate assumptions.
        state.assumptions.forEachBool { id, value ->
            if (state.assignment.boolValue(id) != value) state.assignment.flipBool(id)
        }
        state.assumptions.forEachInt { id, value -> state.assignment.setInt(id, value) }
        state.seedReals(sample)
        state.resetStepCounters()
        if (recompute) state.recompute()
        return true
    }

    /**
     * Greedy-repair pass over [state] right after a restart. Walks vars in randomized order;
     * for each, picks the value that minimizes the current `state.cost` (ties keep the current
     * value). Single forward pass, idempotent on already-feasible states.
     *
     * The point isn't to reach feasibility (LS strategies handle that) but to start from a
     * low-violation pose so the feasibility-fight phase has fewer hard constraints to chase.
     */
    private suspend fun <T> SequenceScope<T?>.greedyRepairPass(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
        onPause: () -> Unit = {},
    ) {
        if (checkpoint == null) {
            greedyInit.run(state) { params.cancellation() }
            return
        }
        val pass = preparedValue(greedyInit.preparation(state), params, checkpoint)
        while (!pass.advance { params.cancellation() }) {
            onPause()
            yield(null)
        }
    }

    /** Undo [move] on [state] so it matches [baselineSnap] again. BoolFlip self-inverts;
     *  IntSet uses [baselineSnap] to recover the old value; Compound reverts each part. */
    private fun revertMove(state: LocalSearchState, move: Move, baselineSnap: Sample) {
        when (move) {
            is Move.BoolFlip -> state.apply(move)

            // self-inverse

            is Move.IntSet -> {
                val old = baselineSnap.intValue(move.varId)
                if (old != state.assignment.intValue(move.varId)) state.apply(Move.IntSet(move.varId, old))
            }

            is Move.RealSet -> {
                val old = baselineSnap.approximateRealValue(move.varId)
                if (old.toRawBits() != state.assignment.realValue(move.varId).toRawBits()) {
                    state.apply(Move.RealSet(move.varId, old))
                }
            }

            is Move.Compound -> {
                // Revert in reverse order so each part sees the post-apply state of the
                // ones after it (mirror of the forward `apply` order).
                for (part in move.parts.reversed()) revertMove(state, part, baselineSnap)
            }
        }
    }

    /**
     * Accumulates per-step move statistics into rounds and flushes each completed round to a
     * strategy's adaptive policies. Created only for a strategy that
     * [SourceDrivenStrategy.wantsRoundFeedback], so common non-adaptive strategies allocate nothing.
     * A restart ends the current round ([endRound]) so a round never spans one.
     */
    private class RoundFeedback(
        private val strategy: SourceDrivenStrategy?,
        private val restartObserver: AdaptivePolicy?,
    ) {
        private val acc = RoundAccumulator()
        private var sinceRound = 0

        /** Record one applied move (a non-worsening move is the round's acceptance signal) and flush
         *  the round to the strategy's schedules and an adaptive restart policy when
         *  [ROUND_FEEDBACK_STEPS] moves have accumulated. */
        fun record(costBefore: Long, costAfter: Long, step: Long) {
            acc.record((costAfter - costBefore).toDouble(), costAfter <= costBefore)
            acc.observeCost(costAfter.toDouble())
            if (++sinceRound >= ROUND_FEEDBACK_STEPS) {
                strategy?.observeRound(acc, step)
                // Temperature-agnostic restart policy keys off the round's cost trend.
                restartObserver?.observe(acc.snapshot(temperature = 1.0, step = step))
                acc.clear()
                sinceRound = 0
            }
        }

        /** Drop the partial round (a restart ended it before it completed). */
        fun endRound() {
            acc.clear()
            sinceRound = 0
        }

        companion object {
            /** A feedback accumulator driving [strategy]'s schedules and/or an adaptive [restart]
             *  policy, or `null` when neither wants per-round feedback (no accumulation overhead). */
            fun of(strategy: SourceDrivenStrategy, restart: RestartPolicy): RoundFeedback? {
                val s = strategy.takeIf { it.wantsRoundFeedback }
                val r = restart as? AdaptivePolicy
                return if (s != null || r != null) RoundFeedback(s, r) else null
            }
        }
    }

    private companion object {
        const val INITIAL_FACTOR_BATCH: Int = 256

        /** Polling interval for cooperative cancellation; see Cancellation.kt. */
        const val CANCEL_CHECK_INTERVAL: Int = 1024

        /** Wall-clock target between cancellation polls (ms): the optimize loop auto-tunes its flip
         *  window down toward this when moves are expensive, so a solve honours its `-t` deadline within
         *  roughly this margin regardless of per-flip cost. */
        const val CANCEL_CHECK_TARGET_MS: Long = 50

        /** Steps between mid-window clock glances (a power of two; masks the countdown). Bounds the
         *  deadline overrun of a window whose steps turn expensive after the window was tuned. */
        const val CANCEL_CLOCK_STRIDE: Int = 16

        /** Mid-window wall-clock budget as a multiple of [CANCEL_CHECK_TARGET_MS]; past it the glance
         *  forces the full poll. Loose enough that a normally-tuned window never trips it. */
        const val CANCEL_OVERRUN_FACTOR: Long = 4

        /** Feasibility-fight moves per round of adaptive-schedule feedback. A round is the batch over
         *  which an adaptive [com.eignex.klause.localsearch.schedule.Schedule] retunes; sized
         *  so the acceptance-ratio estimate is stable yet the schedule still reacts many times. */
        const val ROUND_FEEDBACK_STEPS: Int = 1024
    }
}

/**
 * Whether local search can soundly run on [model]. Every invariant scores any domain exactly: linear rows keep their
 * sum in the narrowest exact form, and the others measure violation with saturating arithmetic, which is zero exactly
 * when the factor holds. The exception is [Cumulative], whose resource profile is a timeline as long as the horizon,
 * so a scheduling factor over a wide domain is declined. A model with continuous columns also needs [completes]: its
 * rows are scored in floating point, so a candidate is a solution only once a [CandidateCompletion] decides it. A
 * portfolio leaves its local-search arms out otherwise.
 */
internal fun localSearchSupports(model: LocalSearchModel, completes: Boolean = false): Boolean {
    val problem = model.problem
    if (problem.numRealVars != 0 && !completes) return false
    val domains = model.domains
    if (domains.all(::isNarrow)) return true
    return problem.factors.none { factor -> factor is Cumulative && factor.intVars.any { !isNarrow(domains[it]) } }
}

// Whether this objective's integer sum can pass the 64-bit range over [domains].
private fun LinearObjective.isWideOver(domains: Array<IntDomain>): Boolean {
    val n = minOf(intCoefficients.size, domains.size)
    return objectiveSumIsWide(boolWeights, IntArray(n) { it }, intCoefficients.copyOf(n), domains)
}
