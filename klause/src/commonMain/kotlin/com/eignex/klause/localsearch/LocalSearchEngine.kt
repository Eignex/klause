@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.localsearch

import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.factor.objective.objectiveSumIsWide
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.movesource.GreedyInit
import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundAccumulator
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchStream
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.asSearchStream
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.result.LocalSearchStatsSink
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.FiniteFactorEnforcer
import com.eignex.klause.solver.withSourceSemantics
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
            preparedBoardRestart = null
            repairTrace?.finish(tracedRestart, RepairEndpoint.Censored)
            tracedRestart = null
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
    internal var projection: LocalSearchPreparation =
        LocalSearchPreparation(problem, model.domains, selection = model.selection)
    internal var invariantNetwork = InvariantNetworkPreparation(
        definitionalSweep, problem.numIntVars, problem.numBoolVars,
    )

    // A finite projection may require theory-owned completion before publication.
    private val completion: CandidateCompletion? = completion
    // An exact rational completion need not have a zero-violation floating representation.
    private val continuousCompletion = completion != null && problem.numRealVars > 0 &&
        problem.numBoolVars == 0 && problem.numIntVars == 0
    private val unscoredCandidateCheck = UnscoredCandidateCheck(problem, model.selection,
        (completion as? FiniteFactorEnforcer)?.enforcingFiniteFactors(problem).orEmpty()).takeIf { it.required }

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
    private var preparedBoardRestart: PreparedBoardRestart? = null
    internal var repairTrace: RepairTrace? = null
    internal var compoundTally: CompoundTally? = null
    private var tracedRestart: RepairTrace.Attempt? = null

    private fun traceStart(state: LocalSearchState, anchor: Sample?, seed: Long?, owner: String) {
        val trace = repairTrace ?: return
        if (!trace.admits(problem.numBoolVars, problem.numIntVars, problem.numRealVars)) return
        tracedRestart = trace.begin(owner, seed, state.assignment.snapshot(), anchor)
    }

    private fun traceStage(stage: String, state: LocalSearchState) {
        if (tracedRestart != null) repairTrace?.record(tracedRestart, stage, state.assignment.snapshot(),
            state.cost.takeUnless { stage == "perturbed" })
    }

    private fun traceEnd(endpoint: RepairEndpoint, state: LocalSearchState) {
        if (tracedRestart != null) {
            repairTrace?.finish(tracedRestart, endpoint, state.assignment.snapshot(), state.cost)
            tracedRestart = null
        }
    }

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

            override fun onFeasibleStall(state: LocalSearchState, sample: Sample, objective: Double) =
                configuredRestart.onFeasibleStall(state, sample, objective)

            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                val previousBest = state.bestCostSeen
                configuredRestart.restart(state, bestSoFar)
                preparedBoardRestart?.afterPolicy(state)
                traceStage("perturbed", state)
                prepareAssignment(state)
                state.recompute()
                state.bestCostSeen = minOf(previousBest, state.cost)
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

    private suspend fun <T> SequenceScope<T?>.applyMoveSteps(
        state: LocalSearchState,
        move: Move,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
        timing: LocalSearchPhaseTimer?,
        onPause: () -> Unit,
    ) {
        if (move is Move.Compound) state.compoundTally?.recordApplied()
        if (checkpoint == null || !state.hasSteppedInvariants) {
            timing.inPhase("application") { state.apply(move) }
            return
        }
        val cursor = state.applySteps(move).iterator()
        while (true) {
            while (params.cancellation()) {
                onPause()
                yield(null)
            }
            val remaining = timing.inPhase("application") { cursor.hasNext() }
            if (!remaining) return
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
        state.compoundTally = compoundTally
        state.violationSoftCap = params.violationSoftCap
        state.weights.normalizeWeightsByClass = params.normalizeWeightsByClass
        installInvariants(state, params, checkpoint)
        if (state.invariants?.hasBooleanLiterals == true) {
            state.booleanRepairs = preparedValue(BooleanRepairClosure.preparation(state), params, checkpoint).closure
        }
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
            seedFrom(state, sample, recompute = false)
            if (reconcile) definitionalSweep?.let {
                it.sweepPinned(state.assignment, state.rootDomains, problem.factors, state.assumptions)
            }
            state.recompute()
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
        return LocalSearchPhaseTimer().also {
            sink.ls.phaseNanos = it::snapshot
            sink.ls.objectiveScoring = it::objectiveScoringSnapshot
        }
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
    }.withSourceSemantics(problem.semantics)

    private fun solveExclusive(params: LocalSearchParams, warm: WarmState?): SolveResult {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        if (!localSearchSupports(model, completion != null)) {
            // Decline a model local search cannot score soundly (see [localSearchSupports]) rather than
            // report a "solution" that may violate it.
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
        // Same soundness boundary as [solve]: stream nothing rather than assignments that may violate factors.
        if (!localSearchSupports(model, completion != null)) return emptySequence()
        val eff = model.pinsUnder(params.assumptions) ?: return emptySequence()
        return guarded(streamImpl(params, eff, warm).filterNotNull().map { it.withSourceSemantics(problem.semantics) })
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
            transform(sequence).map { it.withSourceSemantics(problem.semantics) }
                .asSearchStream(release = ownership::release)
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
            cursor, sink, control, ownership::release, problem.semantics,
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
        minimizeStream(objective, params, warm, SolveStatsSink(backend = "ls")).filterNotNull()
            .map { it.withSourceSemantics(problem.semantics) },
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
            .filterNotNull().map { it.withSourceSemantics(problem.semantics) }.asSearchStream(
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
            // Same soundness boundary as [solve]: an incumbent could violate factors local search cannot score.
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
        val gradient = params.lsObjective?.takeIf {
            params.objectiveGuidance == ObjectiveGuidance.Functional && perMoveInvariants && definitionalSweep != null
        }
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
        return LocalSearchOptimizeRun(params, cursor, sink, control, ownership::release, problem.semantics)
    }

    // The moves one run may make: its own caps, and what is left of the solve's node budget.
    private fun moveCap(params: LocalSearchParams): Long = minOf(
        params.maxFlips,
        params.maxInstructions ?: Long.MAX_VALUE,
        params.nodeBudget?.allowance?.movesLeft() ?: Long.MAX_VALUE,
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
            var lastCheckMs = 0L
            var moves = 0L
            var restartCount = 0L
            var everFeasible = false
            var checkContinuousStart = continuousCompletion
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
                checkContinuousStart = continuousCompletion
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
                        val nowMs = sink?.elapsedMs() ?: 0L
                        val gapMs = maxOf(nowMs - lastCheckMs, 1L)
                        cancelCountdown = (CANCEL_CHECK_INTERVAL.toLong() * CANCEL_CHECK_TARGET_MS / gapMs)
                            .coerceIn(1L, CANCEL_CHECK_INTERVAL.toLong()).toInt()
                        lastCheckMs = nowMs
                    } else if ((cancelCountdown and (CANCEL_CLOCK_STRIDE - 1)) == 0 &&
                        sink != null && sink.elapsedMs() - lastCheckMs >= CANCEL_CHECK_TARGET_MS * CANCEL_OVERRUN_FACTOR
                    ) {
                        cancelCountdown = 0
                    }
                    val continuousCandidate = if (checkContinuousStart) {
                        checkContinuousStart = false
                        state.completionCandidate()
                    } else null
                    if ((state.cost == 0L || continuousCandidate != null) && state.intValuesInDomain()) {
                        if (completion != null && continuousCandidate == null) state.refreshRealRows()
                        if (state.cost != 0L && continuousCandidate == null) continue
                        val decided = decide(
                            state,
                            continuousCandidate ?: state.assignment.snapshot(),
                            params.cancellation,
                            sink?.ls,
                        ) { work ->
                            moves += work
                            flipsSinceYield += work
                        }
                        if (decided !is Completion.Witness) {
                            if (checkpoint != null && decided is Completion.Undecided && params.cancellation()) {
                                checkContinuousStart = continuousCompletion
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
                    val move = timing.inPhase("selection") { strategy.pickMove(state, params.cancellation) }
                    if (move == null) {
                        if (params.cancellation()) {
                            cancelCountdown = 0
                            continue
                        }
                        countedRestart()
                        roundFeedback?.endRound()
                        if (checkpoint?.shouldPause(moves) == true) {
                            reportProgress()
                            yield(null)
                        }
                        continue
                    }
                    applyMoveSteps(state, move, params, checkpoint, timing) {
                        progress.charge(moves)
                        progress.publishResidual()
                        sink?.ls?.recordWork(moves = moves, restarts = restartCount, stalls = 0L)
                    }
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
     * Two-phase per restart attempt: the strategy fights to feasibility, then descends on the
     * objective per its [FeasibleDescent] mode. At a local optimum it restarts; the best feasible
     * objective persists across restarts, so reported incumbents improve monotonically.
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
        traceStart(state, params.initialAssignment, params.randomSeed, "initial-descent")
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
        var liveCompletion: Completion? = null
        var pendingCompletedMove = false

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
        // Read the ratchet handle once per stream; the feasible-incumbent hook uses this capture.
        val ratchetBound = objectiveBound
        // The explicit feasible-phase descent mode — the optimize strategy's when present, else the
        // satisfy strategy's. The cost==0 branch dispatches on it exhaustively (no fall-through), so an
        // arm always optimizes the way it declared and never lands in a default descent by accident.
        val feasibleStrategy = descentStrategy ?: strategy
        val feasibleMode = feasibleStrategy.feasibleDescent
        // Sampling-miss tolerance for a SelfOwned feasible walk: how many consecutive null picks to
        // re-sample before a restart (see [SourceDrivenStrategy.feasibleResampleCap]).
        val feasibleResampleCap = feasibleStrategy.feasibleResampleCap
        val supportsBoardCoverage = feasibleStrategy.supportsBoardCoverage
        var feasibleMisses = 0
        // The feasibility-fight strategy whose moves form the rounds (the unified descent strategy
        // when one drives both phases, else the satisfy strategy).
        val satisfyStrategy: SourceDrivenStrategy = if (unified) descentStrategy else strategy
        // Feed round stats to the unwrapped restart policy so an adaptive one is detected even when a
        // definitional-sweep wrapper stands in for shouldRestart/restart.
        val roundFeedback = RoundFeedback.of(satisfyStrategy, configuredRestart)
        var cancelCountdown = 0
        var lastCheckMs = 0L
        var nextMoveCompletion = 0L
        val completedMoves = if (completion != null && problem.numRealVars > 0 && objective is LinearObjective &&
            objective.realCoefficientValues.any { it != 0.0 } && problem.numBoolVars + problem.numIntVars > 0 &&
            feasibleMode == FeasibleDescent.SelfOwned
        ) CompletedMoveEvaluation(
            state, objective, params.cancellation,
            mayAttempt = {
                totalFlips >= nextMoveCompletion && totalFlips < maxFlips &&
                    params.nodeBudget?.allowance?.movesLeft() != 0L &&
                    checkpoint?.shouldPause(totalFlips) != true
            },
            complete = { candidate ->
                decide(state, candidate, params.cancellation, sink.ls) { work ->
                    totalFlips += work
                    nextMoveCompletion = totalFlips + minOf(work, Long.MAX_VALUE - totalFlips)
                    progress.charge(totalFlips)
                }
            },
            current = { (liveCompletion as? Completion.Witness)?.sample ?: state.assignment.snapshot() },
        ) else null
        var checkInitialCompletion = continuousCompletion || completedMoves != null

        fun reportProgress() {
            (configuredRestart as? IteratedLocalSearchRestart)?.let {
                sink.ls.recordBoardRestarts(it.changedBoardRestarts, it.declinedBoardRestarts)
            }
            progress.optimization(
                totalFlips, restartCount, stallCount, bestObj, bestSample != null,
                bestCostInfeasible, bestFoundAtMs, checkpoint,
            )
        }

        while (totalFlips < maxFlips || pendingCompletedMove) {
            progress.observeResidual()
            while (checkpoint?.shouldPause(totalFlips) == true) {
                reportProgress()
                yield(null)
                cancelCountdown = 0
            }
            if (params.nodeBudget?.allowance?.movesLeft() == 0L && !pendingCompletedMove) break
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
                // overrun the deadline by more than that margin.
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
            if (checkInitialCompletion) {
                checkInitialCompletion = false
                val candidate = if (state.cost != 0L && state.intValuesInDomain()) {
                    state.completionCandidate()
                } else null
                if (candidate != null) {
                    val decided = decide(state, candidate, params.cancellation, sink.ls) { work ->
                        totalFlips += work
                        progress.charge(totalFlips)
                    }
                    if (decided is Completion.Witness) liveCompletion = decided
                    if (params.cancellation()) {
                        checkInitialCompletion = checkpoint != null && liveCompletion == null
                        cancelCountdown = 0
                        continue
                    }
                    if (liveCompletion == null && totalFlips >= maxFlips) continue
                }
            }
            if (state.cost == 0L || liveCompletion is Completion.Witness) {
                pendingCompletedMove = false
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
                    if (state.cost != 0L && liveCompletion !is Completion.Witness) {
                        totalFlips++
                        continue
                    }
                }
                val obj = if (liveCompletion is Completion.Witness) {
                    objective.evaluate(liveCompletion.sample)
                } else if (completion != null || wideObjective) {
                    objective.evaluate(state.assignment.snapshot())
                } else {
                    objective.evaluate(state.assignment)
                }
                if ((liveCompletion is Completion.Witness || obj < bestObj) &&
                    state.intValuesInDomain()
                ) {
                    val decided = liveCompletion ?: decide(
                        state,
                        state.assignment.snapshot(),
                        params.cancellation,
                        sink.ls,
                    ) { work -> totalFlips += work }
                    if (completedMoves != null) liveCompletion = decided
                    if (decided !is Completion.Witness) {
                        if (checkpoint != null && decided is Completion.Undecided && params.cancellation()) {
                            liveCompletion = null
                            reportProgress()
                            yield(null)
                            cancelCountdown = 0
                            continue
                        }
                        liveCompletion = null
                        restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                    val solution = decided.sample
                    if (completion != null) adoptReals(state, solution)
                    val solved = if (completion != null || wideObjective) objective.evaluate(solution) else obj
                    val previous = bestSample
                    val improves = if (completion != null && objective is LinearObjective && previous != null &&
                        solution.symbolicReals == null && solution.algebraicReals == null &&
                        previous.symbolicReals == null && previous.algebraicReals == null
                    ) objective.evaluateExact(solution) < objective.evaluateExact(previous) else solved < bestObj
                    if (improves) {
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
                if (totalFlips >= maxFlips) break
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
                            liveCompletion = null
                            state.reevaluateFactor(problem.numFactors - 1)
                            totalFlips++
                            continue
                        }
                        restarts.onLocalOptimum(state, state.assignment.snapshot(), obj)
                        traceEnd(RepairEndpoint.FeasibleStall, state)
                        liveCompletion = null
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
                    // feasibility-breaking pick is reverted and retried; misses spend the resample allowance.
                    FeasibleDescent.SelfOwned -> {
                        if (feasibleResampleCap == 0 && supportsBoardCoverage && !state.boardCoverage.requested) {
                            state.boardCoverage.request(state)
                        }
                        val moveEvaluation = completedMoves?.takeIf { liveCompletion is Completion.Witness }
                        val m = timing.inPhase("selection") {
                            feasibleStrategy.pickMove(state, params.cancellation, moveEvaluation)
                        }
                        if (m != null) {
                            feasibleMisses = 0
                            if (moveEvaluation != null && moveEvaluation.ordinaryMove != m) {
                                val solution = moveEvaluation.sample(m) ?: moveEvaluation.attempt(m)
                                if (solution != null && !params.cancellation()) {
                                    timing.inPhase("application") {
                                        moveEvaluation.commitMove(m, solution)?.let(state::apply)
                                    }
                                    liveCompletion = Completion.Witness(solution)
                                    pendingCompletedMove = true
                                    if ((moveEvaluation.score(m) ?: 0.0) < 0.0) nextMoveCompletion = totalFlips
                                }
                            } else {
                                val costBefore = state.cost
                                val savedSnap = state.assignment.snapshot()
                                timing.inPhase("application") {
                                    state.apply(m)
                                    if (costBefore == 0L && state.cost != 0L) revertMove(state, m, savedSnap)
                                }
                                if (costBefore != 0L) roundFeedback?.record(costBefore, state.cost, totalFlips + 1)
                                liveCompletion = null
                            }
                            flipsSinceRestart++
                            totalFlips++
                            continue
                        }
                        if (params.cancellation() || totalFlips >= maxFlips ||
                            params.nodeBudget?.allowance?.movesLeft() == 0L ||
                            checkpoint?.shouldPause(totalFlips) == true
                        ) {
                            cancelCountdown = 0
                            continue
                        }
                        if (moveEvaluation != null && state.cost != 0L) {
                            liveCompletion = null
                            continue
                        }
                        // An incomplete pass supplies a feasible anchor, not a local-optimum classification.
                        if (feasibleMisses < feasibleResampleCap) {
                            if (supportsBoardCoverage && !state.boardCoverage.requested) {
                                state.boardCoverage.request(state)
                            }
                            feasibleMisses++
                            flipsSinceRestart++
                            totalFlips++
                            continue
                        }
                        feasibleMisses = 0
                        if (supportsBoardCoverage && !state.boardCoverage.requested) state.boardCoverage.request(state)
                        if (!state.boardCoverage.requested ||
                            (!state.boardCoverage.pending && feasibleStrategy.gradesAllBoardImprovements &&
                                moveEvaluation == null && state.booleanRepairs == null)
                        ) {
                            restarts.onLocalOptimum(state, state.assignment.snapshot(), obj)
                            traceEnd(RepairEndpoint.PolicyLocalOptimum, state)
                        } else {
                            restarts.onFeasibleStall(state, state.assignment.snapshot(), obj)
                            traceEnd(RepairEndpoint.FeasibleStall, state)
                        }
                        liveCompletion = null
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
                liveCompletion = null
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
                if (unified) descentStrategy.pickMove(state, params.cancellation)
                else strategy.pickMove(state, params.cancellation)
            }
            if (move == null) {
                if (params.cancellation()) {
                    cancelCountdown = 0
                    continue
                }
                liveCompletion = null
                restartAndRepair(state, restartAnchor(), params, checkpoint, ::reportProgress)
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                roundFeedback?.endRound() // a restart ends the round; don't span it
                continue
            }
            applyMoveSteps(state, move, params, checkpoint, timing) {
                progress.charge(totalFlips)
                progress.publishResidual()
                sink.ls.recordWork(moves = totalFlips, restarts = restartCount, stalls = stallCount)
            }
            liveCompletion = null
            if (state.cost in 1 until bestCostInfeasible) {
                bestCostInfeasible = state.cost
            }
            flipsSinceRestart++
            totalFlips++
            roundFeedback?.record(costBefore, state.cost, totalFlips)
        }
        reportProgress()
        traceEnd(RepairEndpoint.Censored, state)
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
        timing?.recordObjectiveScoring(if (objective is FunctionalObjective) "functional" else "plain")
        state.shaping.objective = objective
        state.shaping.shapingLambda = (params.costShaping as? CostShaping.Linear)?.lambda ?: 0.0
        state.shaping.feasibilityGated = params.costShaping.feasibilityGated
        timing.inPhase("preparation") {
            prepareSteps(state.prepareObjectiveDelta(objective), params, checkpoint)
        }
        if (params.objectiveGuidance == ObjectiveGuidance.Geometric) {
            timing.inPhase("preparation") {
                prepareSteps(prepareObjectiveProposals(state, params.lsObjective), params, checkpoint)
            }
        }
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
        // Reset the underlying policy: an incumbent from another solve may have a different arity.
        // ScheduleBundle leaves this reset to the engine; the wrapper's reset is a no-op.
        configuredRestart.reset()
        // A caller-supplied starting point stands in for the first random restart; later restarts are the policy's.
        val seeded = seedInitial(state, params, checkpoint, reconcile = false)
        if (!seeded) initialRestart(state, params, checkpoint)
        state
    }

    // Unscored constraints and continuous columns need candidate checks before publication.
    private fun decide(
        state: LocalSearchState,
        candidate: Sample,
        cancellation: Cancellation,
        stats: LocalSearchStatsSink?,
        charge: (Long) -> Unit,
    ): Completion {
        if (unscoredCandidateCheck == null && completion == null) return Completion.Witness(candidate)
        val checkBefore = unscoredCandidateCheck?.requiresReals == false
        val preliminary = if (checkBefore) {
            checkNotNull(unscoredCandidateCheck).check(candidate, state.assumptions, cancellation)
        } else Completion.Witness(candidate)
        val real = if (preliminary is Completion.Witness) {
            completion?.complete(candidate, cancellation) ?: preliminary
        } else preliminary
        val compatible = if (real is Completion.Witness &&
            (real.sample.numBoolVars != candidate.numBoolVars || real.sample.numIntVars != candidate.numIntVars ||
                (problem.numRealVars > 0 && real.sample.numRealVars != candidate.numRealVars) ||
                candidate.hammingDistanceTo(real.sample) != 0)
        ) Completion.Undecided(real.work) else real
        val sourceChecked = if (!checkBefore && compatible is Completion.Witness) {
            when (val checked = unscoredCandidateCheck?.check(compatible.sample, state.assumptions, cancellation)) {
                null, is Completion.Witness -> compatible
                is Completion.Refuted -> Completion.Refuted(checked.factors, compatible.work + checked.work)
                is Completion.Undecided -> Completion.Undecided(compatible.work + checked.work)
            }
        } else compatible
        val decided = if (sourceChecked is Completion.Witness && cancellation()) {
            Completion.Undecided(sourceChecked.work)
        } else sourceChecked
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
        traceEnd(RepairEndpoint.Restarted, state)
        traceStart(state, anchor, params.randomSeed, "restart")
        val policy = configuredRestart as? IteratedLocalSearchRestart
        val observation = if (policy != null && (anchor != null || policy.incumbents.isNotEmpty())) {
            PreparedBoardRestart.capture(state)
        } else null
        preparedBoardRestart = observation
        try {
            state.boardCoverage.invalidate()
            restarts.restart(state, anchor)
            if (definitionalSweep == null && !seedImplicitOnRestart) {
                observation?.afterPolicy(state)
                traceStage("perturbed", state)
            }
            observation?.afterPreparation(state)
            traceStage("prepared", state)
            val repaired = !greedyRepairOnRestart || !isLargeEnoughForGreedy() ||
                greedyRepairPass(state, params, checkpoint, onPause)
            if (repaired && observation != null) policy?.recordBoardRestart(observation.finish(state))
            if (repaired) traceStage("repaired", state)
        } finally {
            preparedBoardRestart = null
        }
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

    /** Run one [GreedyInit] repair pass over [state] right after a restart, pausing at checkpoints. */
    private suspend fun <T> SequenceScope<T?>.greedyRepairPass(
        state: LocalSearchState,
        params: LocalSearchParams,
        checkpoint: LocalSearchCheckpoint?,
        onPause: () -> Unit = {},
    ): Boolean {
        if (checkpoint == null) {
            return greedyInit.pass(state).advance { params.cancellation() }
        }
        val pass = preparedValue(greedyInit.preparation(state), params, checkpoint)
        while (!pass.advance { params.cancellation() }) {
            onPause()
            yield(null)
        }
        return true
    }

    /** Undo [move] on [state] so it matches [baselineSnap] again. BoolFlip self-inverts;
     *  IntSet uses [baselineSnap] to recover the old value; Compound reverts each part. */
    private fun revertMove(state: LocalSearchState, move: Move, baselineSnap: Sample) {
        when (move) {
            is Move.BoolFlip -> state.apply(move)

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

        /** Steps between cooperative cancellation polls. */
        const val CANCEL_CHECK_INTERVAL: Int = 1024

        /** Wall-clock target between cancellation polls (ms): the optimize loop auto-tunes its flip
         *  window down toward this when moves are expensive, so a solve honours its deadline within
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
    val selection = model.selection
    if (problem.factors.indices.any { id ->
            val capabilities = selection?.capabilities(id) ?: problem.factors[id].executionCapabilities()
            capabilities.scoring == com.eignex.klause.solver.ScoringCapability.UNSUPPORTED ||
                (capabilities.assignmentCheck == AssignmentCheckCapability.UNSUPPORTED &&
                    problem.factors[id].variables.reals.isNotEmpty())
        }
    ) return false
    val domains = model.domains
    if (domains.all(::isNarrow)) return true
    return problem.factors.indices.none { id ->
        val factor = problem.factors[id]
        factor is Cumulative && selection?.usesBuiltInScoring(id) != false &&
            factor.intVars.any { !isNarrow(domains[it]) }
    }
}

// Whether this objective's integer sum can pass the 64-bit range over [domains].
private fun LinearObjective.isWideOver(domains: Array<IntDomain>): Boolean {
    val n = minOf(intCoefficients.size, domains.size)
    return objectiveSumIsWide(boolWeights, IntArray(n) { it }, intCoefficients.copyOf(n), domains)
}
