package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.result.LocalSearchStatsSink
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private fun interface SatisfyCheckpoint {
    fun shouldPause(moves: Long): Boolean
}

/**
 * The local-search engine over a [LocalSearchModel]: strategy, restart cadence and the satisfy and minimize loops.
 * All per-draw state — RNG, assignment, factor payloads, the dedup window — lives inside the per-call sequences so
 * concurrent draws never share state.
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
    private val problem: Problem = model.problem

    // Decides each candidate of a model with continuous columns, whose rows are only scored within a tolerance.
    // Null on a model without them, which scores every row exactly and needs no decision.
    private val completion: CandidateCompletion? = completion.takeIf { problem.numRealVars > 0 }

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
                if (seedImplicitOnRestart) state.seedImplicitFeasible()
                definitionalSweep?.sweep(
                    state.assignment,
                    state.rootDomains,
                    problem.factors,
                ) { state.assumptions.isFrozenBool(it) }
                state.recompute()
            }
        }
    }

    private fun installInvariants(state: LocalSearchState) {
        if (!perMoveInvariants) return
        val sweep = definitionalSweep ?: return
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
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
    fun solve(params: LocalSearchParams, warm: WarmState?): SolveResult {
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
        return streamImpl(params, eff, warm).filterNotNull()
    }

    fun resumableSolve(params: LocalSearchParams): ResumableSolve {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        val supported = localSearchSupports(model, completion != null)
        val effective = if (supported) model.pinsUnder(params.assumptions) else null
        val maxInstructions = moveCap(params)
        var token: Cancellation = Cancellation.Never
        var instructions = 0L
        var limit = Long.MAX_VALUE
        var cursor = effective?.let {
            streamImpl(
                params.copy(cancellation = Cancellation { token() }),
                it,
                sink = sink,
                checkpoint = SatisfyCheckpoint { spent ->
                    instructions = spent
                    spent >= limit
                },
            ).iterator()
        }
        return object : ResumableSolve {
            private var verdict: SolveResult? = null
            private var closed = false

            override val isDone: Boolean get() = verdict != null
            override val stats: SolveStats get() = sink.snapshot()
            override val work: Long get() = (instructions / LS_INSTRUCTIONS_PER_WORK).toLong()

            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
                check(!closed) { "the local-search handle is closed" }
                verdict?.let { return it }
                if (!supported) return unknown(TerminationReason.Unsupported)
                if (effective == null) {
                    return if (model.refutesModel) {
                        sink.stop()
                        SolveResult.Unsat(stats = stats).also { verdict = it }
                    } else {
                        unknown(TerminationReason.Unsupported)
                    }
                }
                val available = params.nodeBudget?.movesLeft() ?: Long.MAX_VALUE
                if (available == 0L) return unknown(TerminationReason.BudgetExhausted)
                token = if (sliceMillis == Long.MAX_VALUE) global else {
                    global or Cancellation.until(TimeSource.Monotonic.markNow() + sliceMillis.milliseconds)
                }
                val allowance = if (sliceNodes < 0L) Long.MAX_VALUE else (sliceNodes * LS_INSTRUCTIONS_PER_WORK).toLong()
                limit = instructions + minOf(allowance, available, Long.MAX_VALUE - instructions)
                if (instructions >= limit || token()) return null
                val live = checkNotNull(cursor)
                if (!live.hasNext()) return unknown(TerminationReason.BudgetExhausted)
                val sample = live.next() ?: return if (instructions >= maxInstructions) {
                    unknown(TerminationReason.BudgetExhausted)
                } else {
                    null
                }
                sink.stop()
                return SolveResult.Sat(sample, stats).also { verdict = it }
            }

            private fun unknown(reason: TerminationReason): SolveResult {
                sink.timedOut = reason == TerminationReason.BudgetExhausted
                sink.stop()
                return SolveResult.Unknown(reason, stats).also { verdict = it }
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    cursor = null
                    if (verdict == null) sink.stop()
                }
            }
        }
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
    ): Sequence<MinimizeResult> = sequence {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        if (!localSearchSupports(model, completion != null)) {
            // Same soundness boundary as [solve]: LP-only continuous variables, wide int
            // domains, and wide-coefficient factors are not evaluated by local search, so it could
            // optimize an incumbent that ignores — and may violate — them. Decline.
            sink.stop()
            yield(MinimizeResult.Unknown(TerminationReason.Unsupported, sink.snapshot()))
            return@sequence
        }
        val eff = model.pinsUnder(params.assumptions)
        if (eff == null) {
            sink.stop()
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
        // would score an incumbent better than its own assignment.
        val gradient = params.lsObjective?.takeIf { perMoveInvariants && definitionalSweep != null }
        runMinimizeStream(gradient ?: objective, params, eff, warm, sink)
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
        checkpoint: SatisfyCheckpoint? = null,
    ): Sequence<Sample?> {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val maxFlips = moveCap(params)
        return sequence {
            val state = newSatisfyState(params, effectiveAssumptions, warm, seed)
            var flipsSinceRestart = 0
            // Best-cost-so-far snapshot (even while infeasible): an IteratedLocalSearchRestart
            // perturbs from this instead of full-randomising, accumulating progress across restarts.
            var bestCost = state.cost
            var bestSnap: Sample? = state.assignment.snapshot()
            // Bounded per yield, not per session: maxFlips elapsing without a fresh sample means the
            // search neighbourhood is effectively exhausted, so end the sequence.
            var flipsSinceYield = 0L
            var cancelCountdown = 0
            var moves = 0L
            var charged = 0L
            var restartCount = 0L
            var everFeasible = false
            // Use the unwrapped restart policy so an adaptive one is detected past a sweep wrapper.
            val roundFeedback = RoundFeedback.of(strategy, configuredRestart)

            fun reportProgress() {
                params.nodeBudget?.spendMoves(moves - charged)
                charged = moves
                if (!everFeasible) {
                    sink?.ls?.recordWork(moves = moves, restarts = restartCount, stalls = 0L)
                    sink?.ls?.recordIncumbent(objective = Double.NaN, violation = bestCost.toDouble(), foundAtMs = -1L)
                }
            }

            // A restart transition consumes one unit of the maxFlips/maxInstructions allowance, same as
            // an applied move (see [LocalSearchParams.maxInstructions]).
            fun countedRestart(anchor: Sample?) {
                restarts.restart(state, bestSoFar = anchor)
                moves++
                flipsSinceYield++
                restartCount++
                flipsSinceRestart = 0
            }

            try {
                while (flipsSinceYield < maxFlips) {
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
                        val solution = decide(
                            state,
                            state.assignment.snapshot(),
                            params.cancellation,
                            sink?.ls,
                        ) { work ->
                            moves += work
                            flipsSinceYield += work
                        }
                        if (solution == null) {
                            countedRestart(bestSnap)
                            if (checkpoint?.shouldPause(moves) == true) {
                                reportProgress()
                                yield(null)
                            }
                            continue
                        }
                        if (!everFeasible) {
                            everFeasible = true
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
                        warm?.captureFrom(state)
                        params.nodeBudget?.spendMoves(moves - charged)
                        charged = moves
                        checkpoint?.shouldPause(moves)
                        yield(solution)
                        flipsSinceYield = 0
                        countedRestart(null)
                        bestCost = state.cost
                        bestSnap = state.assignment.snapshot()
                        continue
                    }
                    if (restarts.shouldRestart(flipsSinceRestart)) {
                        countedRestart(bestSnap)
                        roundFeedback?.endRound()
                        if (checkpoint?.shouldPause(moves) == true) {
                            reportProgress()
                            yield(null)
                        }
                        continue
                    }
                    val costBefore = state.cost
                    val move = strategy.pickMove(state)
                    if (move == null) {
                        countedRestart(bestSnap)
                        roundFeedback?.endRound()
                        if (checkpoint?.shouldPause(moves) == true) {
                            reportProgress()
                            yield(null)
                        }
                        continue
                    }
                    state.apply(move)
                    moves++
                    if (state.cost < bestCost) {
                        bestCost = state.cost
                        bestSnap = state.assignment.snapshot()
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
                warm?.captureFrom(state)
                reportProgress()
            }
        }
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
    private suspend fun SequenceScope<MinimizeResult>.runMinimizeStream(
        objective: Objective,
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
        sink: SolveStatsSink,
    ) {
        val state = newMinimizeState(objective, params, effectiveAssumptions, warm)
        // An objective whose sum can pass the 64-bit range is scored from snapshots, which sum it exactly; the live
        // assignment's Long evaluation would wrap.
        val wideObjective = objective is LinearObjective && objective.isWideOver(state.rootDomains)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        // Best-cost (still-infeasible) snapshot for ILS perturbation before feasibility: when
        // bestSample is null an IteratedLocalSearchRestart perturbs from this, so a long
        // feasibility fight accumulates progress.
        var bestCostInfeasible: Long = Long.MAX_VALUE
        var bestCostSnap: Sample? = null
        var flipsSinceRestart = 0
        var totalFlips = 0L
        var restartCount = 0L
        var stallCount = 0L
        var bestFoundAtMs = -1L
        val maxFlips = moveCap(params)
        var charged = 0L
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

        // The anchor for a restart: refresh from the pool first, then prefer the incumbent, falling back
        // to [fallback] (a best-cost-infeasible snapshot) when no feasible incumbent exists yet.
        fun restartAnchor(fallback: Sample?): Sample? {
            pooled.poll(bestObj)?.let { (sample, obj) ->
                bestObj = obj
                bestSample = sample
            }
            return bestSample ?: fallback
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

        while (totalFlips < maxFlips) {
            if (cancelCountdown-- <= 0) {
                if (params.cancellation()) {
                    cancelled = true
                    break
                }
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
                if (params.cancellation()) {
                    cancelled = true
                    break
                }
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
                    val solution = decide(
                        state,
                        state.assignment.snapshot(),
                        params.cancellation,
                        sink.ls,
                    ) { work -> totalFlips += work }
                    if (solution == null) {
                        restartAndRepair(state, restartAnchor(null))
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                    if (completion != null) adoptReals(state, solution)
                    val solved = if (completion != null || wideObjective) objective.evaluate(solution) else obj
                    if (solved < bestObj) {
                        bestObj = solved
                        bestSample = solution
                        bestFoundAtMs = sink.elapsedMs()
                        params.onEvent?.invoke(SearchEvent.Incumbent(solved))
                        pooled.publish(solution, solved)
                        params.nodeBudget?.spendMoves(totalFlips - charged)
                        charged = totalFlips
                        yield(MinimizeResult.BestFound(solution, solved, TerminationReason.BudgetExhausted))
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
                        restartAndRepair(state, restartAnchor(null))
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
                        val m = (descentStrategy ?: strategy).pickMove(state)
                        if (m != null) {
                            feasibleMisses = 0
                            val savedSnap = state.assignment.snapshot()
                            state.apply(m)
                            if (state.cost != 0L) revertMove(state, m, savedSnap)
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
                        restartAndRepair(state, restartAnchor(null))
                        stallCount++
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                }
            }
            if (restarts.shouldRestart(flipsSinceRestart)) {
                restartAndRepair(state, restartAnchor(bestCostSnap))
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                roundFeedback?.endRound()
                continue
            }
            // Pre-feasibility: drive through the unified strategy when one is configured, else the
            // satisfy-mode strategy.
            val costBefore = state.cost
            val move = if (unified) descentStrategy.pickMove(state) else strategy.pickMove(state)
            if (move == null) {
                restartAndRepair(state, restartAnchor(bestCostSnap))
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                roundFeedback?.endRound() // a restart ends the round; don't span it
                continue
            }
            state.apply(move)
            if (state.cost in 1 until bestCostInfeasible) {
                bestCostInfeasible = state.cost
                bestCostSnap = state.assignment.snapshot()
            }
            flipsSinceRestart++
            totalFlips++
            roundFeedback?.record(costBefore, state.cost, totalFlips)
        }
        warm?.captureFrom(state)
        val reason = if (cancelled) TerminationReason.Cancelled else TerminationReason.BudgetExhausted
        sink.stop()
        sink.timedOut = reason == TerminationReason.BudgetExhausted
        sink.ls.recordWork(moves = totalFlips, restarts = restartCount, stalls = stallCount)
        params.nodeBudget?.spendMoves(totalFlips - charged)
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
    private fun newMinimizeState(
        objective: Objective,
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
    ): LocalSearchState {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = LocalSearchState(model, Random(seed), effectiveAssumptions)
        state.violationSoftCap = params.violationSoftCap
        state.weights.normalizeWeightsByClass = params.normalizeWeightsByClass
        installInvariants(state)
        warm?.applyTo(state)
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
        val seeded = params.initialAssignment?.let { seedFrom(state, it) } ?: false
        if (seeded) {
            // Reconcile the warm-loaded assignment exactly as the restart path does: the seed sets only
            // the variables its producing engine emitted, so any *defined* variable must be re-derived
            // from the seeded decision variables — otherwise a definitional constraint reads as violated
            // and the engine fights up from a spuriously-infeasible state, discarding the warm start.
            definitionalSweep?.let { sweep ->
                sweep.sweep(state.assignment, state.rootDomains, problem.factors) {
                    state.assumptions.isFrozenBool(it)
                }
                state.recompute()
            }
        } else {
            restarts.restart(state, bestSoFar = null)
        }
        // Greedy-repair is gated on problem size: on tiny problems LS reaches feasibility in
        // microseconds and the repair pass is pure overhead. Skip it on a warm start: the seed is
        // already feasible, and the repair sweep is objective-blind (it accepts any flip that doesn't
        // raise cost), so on a cost-0 seed it would wander across equal-cost feasibles and discard the
        // seed's objective — defeating the warm start.
        if (greedyRepairOnRestart && isLargeEnoughForGreedy() && !seeded) greedyRepairPass(state)
        return state
    }

    /** Build and prime the per-call [LocalSearchState] for a [streamImpl] satisfy draw: config the
     *  violation cap / weight normalisation, install invariants and warm state, reset the (possibly
     *  reused) restart policy's per-solve state, then take the first random restart. */
    private fun newSatisfyState(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState?,
        seed: Long,
    ): LocalSearchState {
        val state = LocalSearchState(model, Random(seed), effectiveAssumptions)
        state.violationSoftCap = params.violationSoftCap
        state.weights.normalizeWeightsByClass = params.normalizeWeightsByClass
        installInvariants(state)
        warm?.applyTo(state)
        // A restart policy instance can be reused across solves (a tuning campaign runs one
        // recipe over many problems); clear its per-solve state so a stale incumbent — possibly
        // of a different variable arity — can't leak in and be indexed against this problem.
        // ScheduleBundle leaves restart reset to the engine; reset the underlying policy, not
        // the `restarts` wrapper (whose reset is the interface no-op).
        configuredRestart.reset()
        // A caller-supplied starting point stands in for the first random restart; later restarts are the policy's.
        // Streaming has no notion of "best so far" to anchor an adaptive restart
        // around — pass null so policies that need a sample fall back to a fresh
        // random restart.
        val seeded = params.initialAssignment?.let { seedFrom(state, it) } ?: false
        if (!seeded) restarts.restart(state, bestSoFar = null)
        return state
    }

    /**
     * The solution [candidate] stands for, or null when it is none. A model without continuous columns scores every
     * row exactly, so its candidate is its own solution; otherwise the [completion] decides it, the work that took
     * goes to [charge], and the rows a refutation names gain weight so the search steers away from the same failure.
     */
    private fun decide(
        state: LocalSearchState,
        candidate: Sample,
        cancellation: Cancellation,
        stats: LocalSearchStatsSink?,
        charge: (Long) -> Unit,
    ): Sample? {
        val completion = completion ?: return candidate
        val decided = completion.complete(candidate, cancellation)
        charge(decided.work)
        stats?.recordCompletion(refuted = decided is Completion.Refuted, undecided = decided is Completion.Undecided)
        return when (decided) {
            is Completion.Witness -> decided.sample

            is Completion.Refuted -> {
                val weights = state.weights.factorWeights
                for (f in decided.factors) weights[f] += 1.0
                null
            }

            is Completion.Undecided -> null
        }
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
    private fun restartAndRepair(state: LocalSearchState, anchor: Sample?) {
        restarts.restart(state, anchor)
        if (greedyRepairOnRestart && isLargeEnoughForGreedy()) greedyRepairPass(state)
    }

    /** True when the problem is big enough that the post-restart greedy-repair sweep pays for
     *  itself; tiny problems reach feasibility in microseconds and the sweep is pure overhead. */
    private fun isLargeEnoughForGreedy(): Boolean = (problem.numBoolVars + problem.numIntVars) >= 32

    /** Load [sample] into [state]'s assignment (re-pinning assumed slots), reset the tabu/CC
     *  epoch, and recompute cost/degrees — the warm-start seed path for
     *  [LocalSearchParams.initialAssignment]. Returns false (leaving the state untouched for a
     *  normal random restart) when the sample's arity doesn't match this problem. */
    private fun seedFrom(state: LocalSearchState, sample: Sample): Boolean {
        if (sample.bools.size != problem.numBoolVars || sample.ints.size != problem.numIntVars) return false
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.ints[i])
        // Respect caller pins as restart() does, so a seed can't violate assumptions.
        state.assumptions.forEachBool { id, value ->
            if (state.assignment.boolValue(id) != value) state.assignment.flipBool(id)
        }
        state.assumptions.forEachInt { id, value -> state.assignment.setInt(id, value) }
        state.seedReals(sample)
        state.resetStepCounters()
        state.recompute()
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
    private fun greedyRepairPass(state: LocalSearchState) = greedyInit.run(state)

    /** Undo [move] on [state] so it matches [baselineSnap] again. BoolFlip self-inverts;
     *  IntSet uses [baselineSnap] to recover the old value; Compound reverts each part. */
    private fun revertMove(state: LocalSearchState, move: Move, baselineSnap: Sample) {
        when (move) {
            is Move.BoolFlip -> state.apply(move)

            // self-inverse

            is Move.IntSet -> {
                val old = baselineSnap.ints[move.varId]
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

/**
 * Whether local search scores the finite model [problem] entirely in its plain `Long` invariants: no continuous
 * column, every domain narrow, no over-64-bit row. A mixed pool builds local-search arms only on such a model; on a
 * wider one they would take slots from arms that search it without that cost.
 */
internal fun localSearchIsExact(problem: BakedProblem): Boolean =
    problem.numRealVars == 0 && problem.rootIntDomainsInPlace.all(::isNarrow) &&
        problem.factors.none {
            (it is Linear && it.wideConstants != null) ||
                (it is ReifiedLinear && it.wideConstants != null)
        }
