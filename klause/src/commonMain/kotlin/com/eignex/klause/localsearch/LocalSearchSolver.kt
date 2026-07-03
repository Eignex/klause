package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.GreedyInit
import com.eignex.klause.localsearch.movesource.PairSwap
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundAccumulator
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import kotlin.random.Random

/**
 * Local-search [Solver] around a [Problem]. The solver itself only carries engine setup
 * (strategy, restart cadence). All per-draw state — RNG, assignment, factor payloads, the
 * dedup window — lives inside the per-call sequences so concurrent draws never share state.
 *
 * Three call kinds, each accepting a [LocalSearchParams]:
 *
 *  - [solve] — return a single [SolveResult]; LS never reports `Unsat`.
 *  - [sample] / [enumerate] — both stream independent feasible draws with replacement.
 *    Local search has no notion of a "next" model, so enumerate is just a sample stream;
 *    duplicates may appear. Use [com.eignex.klause.backtrack.BacktrackSolver] when
 *    true without-replacement enumeration is required.
 */
class LocalSearchSolver(
    override val problem: Problem,
    /** SourceDrivenStrategy used during the satisfy phase. Default is adaptive probSAT with the
     *  OrImproving tabu aspiration: a continuous-weighted candidate distribution that handles
     *  mixed-degree factor problems, self-tuning by widening on stalls and re-sharpening on
     *  progress; the aspiration admits individually improving moves the tenure window would block. */
    val strategy: SourceDrivenStrategy = ProbSat.adaptive(
        tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
    ),
    /** SourceDrivenStrategy for the feasibility-fight phase of [minimize]. `null` reuses [strategy].
     *  Override to decouple satisfy-mode and minimize-mode strategies; the common case is satisfy
     *  [ProbSat.adaptive] + minimize [Cbls] for decomposed CP problems, where CBLS's weighted-violation
     *  gradient descends the objective on instances where probSAT plateaus. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    /** Cap on pair-swap candidates considered before the objective descent gives up at a
     *  single-flip local minimum. Pair swaps escape plateaus where every single flip
     *  breaks feasibility but a coordinated 2-flip preserves it (common in
     *  binary-decision optimization like knapsack / packing). 0 disables pair-swap. */
    val pairSwapBudget: Int = 256,
    /** When true (default), restarts run a greedy-repair pass after randomizing so the search starts
     *  closer to feasibility. The pass walks vars in randomized order and picks the value that
     *  minimizes immediate violation contribution. Idempotent and bounded by the variable count. */
    val greedyRepairOnRestart: Boolean = true,
    /** Optional definitional sweep (see [DefinitionalSweep]): after every restart's randomization,
     *  defined (aux) vars are *evaluated* bottom-up from the free decision vars instead of left
     *  random, so decomposed models start each restart at the "only real constraints violated"
     *  frontier. Null = behavior unchanged. */
    val definitionalSweep: DefinitionalSweep? = null,
    /** Per-move one-way invariants (opt-in): maintain [definitionalSweep]'s definitions incrementally
     *  after every applied move and exclude defined vars from move generation, shrinking the move
     *  space to true decision variables. Requires [definitionalSweep]; the restart-time sweep stays
     *  active as the full (re)initializer. */
    val perMoveInvariants: Boolean = false,
    /** Implicit-solving feasible init (opt-in): after each restart's randomization the engine seeds
     *  elected structural globals (see [LocalSearchState.electedImplicit]) into a feasible
     *  configuration via [com.eignex.klause.localsearch.Invariant.seedFeasible] — an all-different becomes a
     *  partial permutation, a circuit a single tour — so the search starts inside those constraints'
     *  feasible region and their structure-preserving moves are productive from the first step. */
    val seedImplicitOnRestart: Boolean = false,
) : Solver<LocalSearchParams>,
    Optimizer<LocalSearchParams> {

    private val satisfiedStructured: SatisfiedStructured = SatisfiedStructured.all()

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
                    problem.intDomains,
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

    override fun describe(params: LocalSearchParams): String {
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

    override fun solve(params: LocalSearchParams): SolveResult = solveInternal(params, warm = null)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = samplesInternal(params, warm = null)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> = enumerateInternal(params, warm = null)

    /** Return a [LocalSearchSession] that persists DDFW-style factor weights across
     *  calls and maintains an assumption stack. Backend-specific override of
     *  [Solver.session]'s default `StatelessSession`. */
    override fun session(): LocalSearchSession = LocalSearchSession(this)

    internal fun solveInternal(params: LocalSearchParams, warm: WarmState?): SolveResult {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        val eff = effectiveAssumptions(params.assumptions)
        if (eff == null) {
            sink.stop()
            return SolveResult.Unsat(stats = sink.snapshot())
        }
        val sample = sampleInternal(params, eff, warm, sink)
        sink.stop()
        return if (sample != null) {
            SolveResult.Sat(sample, sink.snapshot())
        } else {
            sink.timedOut = true
            SolveResult.Unknown(TerminationReason.BudgetExhausted, sink.snapshot())
        }
    }

    internal fun samplesInternal(params: LocalSearchParams, warm: WarmState?): Sequence<Sample> {
        val eff = effectiveAssumptions(params.assumptions) ?: return emptySequence()
        return streamImpl(params, eff, warm)
    }

    internal fun enumerateInternal(params: LocalSearchParams, warm: WarmState?): Sequence<Sample> =
        samplesInternal(params, warm)

    private fun sampleInternal(
        params: LocalSearchParams,
        eff: Assumptions,
        warm: WarmState?,
        sink: SolveStatsSink? = null,
    ): Sample? = streamImpl(params, eff, warm, sink).firstOrNull()

    /**
     * Fold the bake-time propagation result + per-call assumptions into the effective pin set
     * the search will see. Returns `null` iff propagation derived Unsat — a sound proof
     * (translates to [SolveResult.Unsat] / empty sequence / `null` minimize result).
     */
    private fun effectiveAssumptions(callAssumptions: Assumptions): Assumptions? {
        val baked = problem.baked
        if (baked is PropagationResult.Unsat) return null
        baked as PropagationResult.Implied
        if (callAssumptions.isEmpty) {
            return if (baked.isEmpty) Assumptions.None else baked.toAssumptions()
        }
        return when (val r = problem.propagate(callAssumptions)) {
            is PropagationResult.Unsat -> null
            is PropagationResult.Implied -> callAssumptions.mergedWith(r.toAssumptions())
        }
    }

    /**
     * Best-effort linear-objective minimisation under hard constraints. Reaches feasibility
     * via the configured [strategy] (WalkSat/probSAT-style), then descends on the objective
     * by greedy single-flip / single-set moves that keep `cost == 0`. Whenever the
     * descent stalls or the budget per attempt elapses, the search restarts with a fresh
     * randomized assignment; the best feasible objective seen across all attempts is
     * returned.
     *
     * Scores moves by the O(arity) incremental [LinearObjective] delta, or by
     * [LocalSearchParams.lsObjective]'s `deltaIfApplied` when the caller supplies that gradient
     * view of the same objective (the functionally-defined-cone case).
     */
    override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
        improvementsInternal(objective, params, warm = null).last()

    override fun improvements(objective: LinearObjective, params: LocalSearchParams): Sequence<MinimizeResult> =
        improvementsInternal(objective, params, warm = null)

    /**
     * Internal minimize entry point. Local search is **incomplete**: it never proves
     * optimality or infeasibility. So the verdict is always either
     * [MinimizeResult.BestFound] (a feasible was reached) or [MinimizeResult.Unknown]
     * (budget gone before feasibility). Bake-time-Unsat is the one case we can prove
     * Infeasible — propagation derived it before LS started.
     */
    internal fun minimizeInternal(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
    ): MinimizeResult = improvementsInternal(objective, params, warm).last()

    /**
     * Streaming search. Yields one [MinimizeResult.BestFound] per new incumbent
     * established during the inner loop (i.e. every time `obj < bestObj` strictly
     * improves), followed by exactly one terminal verdict. Consumers can react to each
     * improvement before search continues; `improvements(...).last()` is equivalent to
     * the single-shot [minimize] semantics.
     *
     * The terminal verdict is [MinimizeResult.Infeasible] when propagation rules the
     * problem out before any LS work happens; otherwise either a final
     * [MinimizeResult.BestFound] (carrying the same sample as the last intermediate
     * yield, with the real termination reason) or [MinimizeResult.Unknown] when LS
     * never reached feasibility.
     */
    internal fun improvementsInternal(
        objective: LinearObjective,
        params: LocalSearchParams,
        warm: WarmState?,
    ): Sequence<MinimizeResult> = sequence {
        val sink = SolveStatsSink(backend = "ls")
        sink.start()
        val eff = effectiveAssumptions(params.assumptions)
        if (eff == null) {
            sink.stop()
            yield(MinimizeResult.Infeasible(stats = sink.snapshot()))
            return@sequence
        }
        // Descend the caller's gradient view when one is supplied (it agrees with the linear
        // objective at every feasible point, see [LocalSearchParams.lsObjective]).
        runMinimizeStream(params.lsObjective ?: objective, params, eff, warm, sink)
    }

    /** Solve once and return a [SolveResult]. */
    fun solve(): SolveResult = solve(LocalSearchParams())

    /** Draw a single diverse sample, or null if none exists. */
    fun sample(): SampleResult = sample(LocalSearchParams())

    /** Lazily draw diverse samples. */
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())

    /** Lazily enumerate distinct models. */
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())

    /** Optimise against [objective] under the hard constraints. */
    fun minimize(objective: LinearObjective): MinimizeResult = minimize(objective, LocalSearchParams())

    private fun streamImpl(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState? = null,
        sink: SolveStatsSink? = null,
    ): Sequence<Sample> {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val maxFlips = minOf(params.maxFlips, params.maxInstructions ?: Long.MAX_VALUE)
        return sequence {
            val state = LocalSearchState(problem, Random(seed), effectiveAssumptions)
            state.violationSoftCap = params.violationSoftCap
            state.normalizeWeightsByClass = params.normalizeWeightsByClass
            installInvariants(state)
            warm?.applyTo(state)
            // Streaming has no notion of "best so far" to anchor an adaptive restart
            // around — pass null so policies that need a sample fall back to a fresh
            // random restart.
            restarts.restart(state, bestSoFar = null)
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
            var restartCount = 0L
            var everFeasible = false
            // Use the unwrapped restart policy so an adaptive one is detected past a sweep wrapper.
            val roundFeedback = RoundFeedback.of(strategy, configuredRestart)

            try {
                while (flipsSinceYield < maxFlips) {
                    if (cancelCountdown-- <= 0) {
                        if (params.cancellation()) return@sequence
                        cancelCountdown = CANCEL_CHECK_INTERVAL
                    }
                    if (state.cost == 0L) {
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
                        val snap = state.assignment.snapshot()
                        // Sync warm state on every yield so streaming consumers that never drain the
                        // sequence still see captured weights.
                        warm?.captureFrom(state)
                        yield(snap)
                        flipsSinceYield = 0
                        restarts.restart(state, bestSoFar = null)
                        restartCount++
                        bestCost = state.cost
                        bestSnap = state.assignment.snapshot()
                        flipsSinceRestart = 0
                        continue
                    }
                    if (restarts.shouldRestart(flipsSinceRestart)) {
                        restarts.restart(state, bestSoFar = bestSnap)
                        restartCount++
                        flipsSinceRestart = 0
                        roundFeedback?.endRound()
                        continue
                    }
                    val costBefore = state.cost
                    val move = strategy.pickMove(state)
                    if (move == null) {
                        restarts.restart(state, bestSoFar = bestSnap)
                        restartCount++
                        flipsSinceRestart = 0
                        roundFeedback?.endRound()
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
                }
            } finally {
                // Sync learned weights back into warm state on natural exit or consumer cancel.
                // Abandoned sequences may not fire this; accepted loss.
                warm?.captureFrom(state)
                // Reached only when the search never hit feasibility (the feasible path records at the
                // yield above and suspends there). Report the lowest residual cost as the incumbent
                // violation so an UNKNOWN run still shows how close it got.
                if (!everFeasible) {
                    sink?.ls?.recordWork(moves = moves, restarts = restartCount, stalls = 0L)
                    sink?.ls?.recordIncumbent(objective = Double.NaN, violation = bestCost.toDouble(), foundAtMs = -1L)
                }
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
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = LocalSearchState(problem, Random(seed), effectiveAssumptions)
        state.violationSoftCap = params.violationSoftCap
        state.normalizeWeightsByClass = params.normalizeWeightsByClass
        installInvariants(state)
        warm?.applyTo(state)
        // Plumb shaping into the state so strategies consulting shapedBreakScore see the objective
        // during pre-feasibility moves. Only CostShaping.Linear contributes a non-zero lambda;
        // FeasibilityFirst leaves it at 0.0, identical to the no-shaping path.
        state.objective = objective
        state.shapingLambda = (params.costShaping as? CostShaping.Linear)?.lambda ?: 0.0
        // Warm-start from a caller-supplied (arity-compatible) assignment instead of a random
        // restart; null by default. See [LocalSearchParams.initialAssignment].
        val seeded = params.initialAssignment?.let { seedFrom(state, it) } ?: false
        if (seeded) {
            // Reconcile the warm-loaded assignment exactly as the restart path does: the seed sets only
            // the variables its producing engine emitted, so any *defined* variable must be re-derived
            // from the seeded decision variables — otherwise a definitional constraint reads as violated
            // and the engine fights up from a spuriously-infeasible state, discarding the warm start.
            definitionalSweep?.let { sweep ->
                sweep.sweep(state.assignment, problem.intDomains, problem.factors) {
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
        val largeEnoughForGreedy = (problem.numBoolVars + problem.numIntVars) >= 32
        if (greedyRepairOnRestart && largeEnoughForGreedy && !seeded) greedyRepairPass(state)

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
        val maxFlips = minOf(params.maxFlips, params.maxInstructions ?: Long.MAX_VALUE)
        val shaping = params.costShaping
        var cancelled = false

        // Each restart counts as one unit of work against maxFlips; otherwise a degenerate objective
        // on a constraint-free problem would loop forever (cost stays 0, descent never improves, and
        // the restart path wouldn't bump totalFlips).
        // Phase strategy: when optimizeStrategy is feasibility-aware (gates objective behind cost==0)
        // it drives both phases. Else split phases (strategy for satisfy, optimizeStrategy for
        // descent) for non-unified strategies that bail at feasibility.
        val descentStrategy = optimizeStrategy
        val unified = descentStrategy?.drivesObjectiveDescent == true
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
                val obj = objective.evaluate(state.assignment)
                if (obj < bestObj) {
                    bestObj = obj
                    val snap = state.assignment.snapshot()
                    bestSample = snap
                    bestFoundAtMs = sink.elapsedMs()
                    params.onEvent?.invoke(SearchEvent.Incumbent(obj))
                    yield(MinimizeResult.BestFound(snap, obj, TerminationReason.BudgetExhausted))
                }
                // Descent options in order of informedness; each gets one chance before falling
                // through to the next, and stalling all of them triggers a restart. Cheapest first:
                // greedy single-flip, then CBLS weighted scoring, factor-aware structured moves, then
                // the random pair-swap fallback.
                val descended = if (shaping.feasibilityGated) {
                    greedyObjectiveStep(state, objective, params.cancellation)
                } else {
                    shapedDescentStep(state, objective, shaping, params.cancellation)
                }
                if (descended) {
                    flipsSinceRestart++
                    totalFlips++
                    continue
                }
                // CBLS descent: ask the optimizeStrategy for an objective-improving move. SAT-style
                // strategies return null at feasibility, so this is a no-op for them.
                if (descentStrategy != null) {
                    val m = descentStrategy.pickMove(state)
                    if (m != null) {
                        // Commit only if the move keeps feasibility AND improves the objective — CBLS
                        // may propose feasibility-breaking moves we don't want in the gated phase.
                        val baseObj = objective.evaluate(state.assignment)
                        // Snapshot only to undo a rejected move; both objective reads are live.
                        val savedSnap = state.assignment.snapshot()
                        state.apply(m)
                        if (state.cost == 0L && objective.evaluate(state.assignment) < baseObj) {
                            flipsSinceRestart++
                            totalFlips++
                            continue
                        }
                        revertMove(state, m, savedSnap)
                    }
                }
                if (structuredMoveStep(state, objective, params.cancellation)) {
                    flipsSinceRestart++
                    totalFlips++
                    continue
                }
                if (pairSwapBudget > 0 && largeEnoughForGreedy &&
                    pairSwapStep(state, objective, pairSwapBudget, params.cancellation)
                ) {
                    flipsSinceRestart++
                    totalFlips++
                    continue
                }
                // A local optimum (all descent steps failed) is infrequent, so snapshotting for the
                // restart policy here stays off the per-iteration hot path.
                restarts.onLocalOptimum(state, state.assignment.snapshot(), obj)
                restarts.restart(state, bestSample)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
                stallCount++
                restartCount++
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            if (restarts.shouldRestart(flipsSinceRestart)) {
                restarts.restart(state, bestSample ?: bestCostSnap)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
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
                restarts.restart(state, bestSample ?: bestCostSnap)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
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
        state.resetStepCounters()
        state.recompute()
        return true
    }

    /**
     * Greedy hill-climbing on the objective among feasibility-preserving single-variable
     * moves. Considers a flip on each bool var and a ±1 step on each int var (clamped to
     * the int's domain). Picks the candidate that strictly lowers the objective the most
     * while keeping `cost == 0`. Returns `true` if it advanced.
     *
     * Bool flips are evaluated by applying-then-reverting on the live state so the
     * incremental cost path runs naturally; int sets do the same with the saved old
     * value.
     */
    private fun greedyObjectiveStep(
        state: LocalSearchState,
        objective: Objective,
        cancellation: Cancellation,
    ): Boolean {
        // Score each candidate via netDelta + objectiveDelta, no snapshot/evaluate per candidate —
        // only on commit. Every objective reaching the descent is incremental, so no evaluate fallback.
        val baseCost = state.cost
        val poll = IntArray(1)
        var bestDelta = 0.0
        var bestMove: Move? = null

        var b = 0
        while (b < problem.numBoolVars) {
            if (pollCancel(poll, cancellation)) return commitBest(state, bestMove)
            val v = b++
            if (state.assumptions.isFrozenBool(v)) continue
            val move = Move.BoolFlip(v)
            if (baseCost + state.netDelta(move) != 0L) continue // not feasibility-preserving
            val delta = state.objectiveDelta(objective, move) ?: continue
            if (delta < bestDelta) {
                bestDelta = delta
                bestMove = move
            }
        }

        var i = 0
        while (i < problem.numIntVars) {
            if (pollCancel(poll, cancellation)) return commitBest(state, bestMove)
            val v = i++
            if (state.assumptions.isFrozenInt(v)) continue
            val cur = state.assignment.intValue(v)
            val d = problem.intDomains[v]
            for (target in intArrayOf(cur - 1, cur + 1)) {
                if (target !in d) continue // sparse-aware: rejects holes
                val move = Move.IntSet(v, target)
                if (baseCost + state.netDelta(move) != 0L) continue
                val delta = state.objectiveDelta(objective, move) ?: continue
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestMove = move
                }
            }
        }

        return commitBest(state, bestMove)
    }

    /** Poll [cancellation] once every [CANCEL_CHECK_INTERVAL] candidates, advancing the
     *  single-element [counter] box so the count carries across calls within one descent scan.
     *  Returns true when the candidate loop should abort — keeps the O(numVars) descent steps
     *  deadline-responsive. */
    private fun pollCancel(counter: IntArray, cancellation: Cancellation): Boolean {
        if (++counter[0] < CANCEL_CHECK_INTERVAL) return false
        counter[0] = 0
        return cancellation()
    }

    /** Commit [bestMove] if a descent improver was found, returning whether a move was
     *  applied. Shared tail of the best-improvement descent steps. */
    private fun commitBest(state: LocalSearchState, bestMove: Move?): Boolean {
        if (bestMove == null) return false
        state.apply(bestMove)
        return true
    }

    /**
     * Shaped-cost greedy step. Picks the single-variable move (bool flip / int ±1) whose
     * post-state shaped score `shape(violationCount, objective)` is strictly less than
     * the current shaped score. Unlike [greedyObjectiveStep], may step into infeasibility
     * — the main minimize loop then drives back via the configured strategy.
     */
    private fun shapedDescentStep(
        state: LocalSearchState,
        objective: Objective,
        shaping: CostShaping,
        cancellation: Cancellation,
    ): Boolean {
        // Anchor on one baseline evaluate, then score each candidate's shaped value from
        // (baseCost + netDelta, baselineObj + objectiveDelta) — no per-candidate snapshot/evaluate
        // and no apply/revert. Every reachable objective is incremental, so no evaluate fallback.
        val baseCost = state.cost
        val baselineObj = objective.evaluate(state.assignment)
        val poll = IntArray(1)
        var bestShaped = shaping.shape(baseCost, baselineObj)
        var bestMove: Move? = null

        var b = 0
        while (b < problem.numBoolVars) {
            if (pollCancel(poll, cancellation)) return commitBest(state, bestMove)
            val v = b++
            if (state.assumptions.isFrozenBool(v)) continue
            val move = Move.BoolFlip(v)
            val delta = state.objectiveDelta(objective, move) ?: continue
            val shaped = shaping.shape(baseCost + state.netDelta(move), baselineObj + delta)
            if (shaped < bestShaped) {
                bestShaped = shaped
                bestMove = move
            }
        }

        var i = 0
        while (i < problem.numIntVars) {
            if (pollCancel(poll, cancellation)) return commitBest(state, bestMove)
            val v = i++
            if (state.assumptions.isFrozenInt(v)) continue
            val cur = state.assignment.intValue(v)
            val d = problem.intDomains[v]
            for (target in intArrayOf(cur - 1, cur + 1)) {
                if (target !in d) continue // sparse-aware: rejects holes
                val move = Move.IntSet(v, target)
                val delta = state.objectiveDelta(objective, move) ?: continue
                val shaped = shaping.shape(baseCost + state.netDelta(move), baselineObj + delta)
                if (shaped < bestShaped) {
                    bestShaped = shaped
                    bestMove = move
                }
            }
        }

        return commitBest(state, bestMove)
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

    /**
     * Factor-aware structured descent step. Collects [com.eignex.klause.localsearch.Invariant.proposeStructuredMoves]
     * from every factor — each factor pushes moves it knows preserve its own satisfaction
     * (e.g. `Linear EQ` pair-shifts that keep the sum, `Cardinality.exactlyOne` swaps that
     * keep the count). The engine scores each by objective delta on a temporary apply,
     * applies the best feasibility-preserving improver, and commits.
     *
     * Returns `true` and commits if an improving structured move exists. Returns `false`
     * if no factor proposed an improving feasibility-preserving move within the collected
     * set; the caller falls back to random pair-swap.
     *
     * Cost: one `proposeStructuredMoves` call per factor (each factor caps its own
     * proposal count) plus a scoring apply+revert per proposed move. Bounded by the sum
     * of per-factor caps.
     */
    private fun structuredMoveStep(
        state: LocalSearchState,
        objective: Objective,
        cancellation: Cancellation,
    ): Boolean {
        val sink = state.moveSink
        sink.clear()
        // Only consult currently-satisfied factors; a violated factor proposes repair moves (which
        // run before objective descent), so the enumerate-all source skips them.
        satisfiedStructured.generate(state, sink)
        val proposed = sink.list
        if (proposed.isEmpty()) return false
        val poll = IntArray(1)
        val best = bestStructuredIncremental(state, objective, proposed, poll, cancellation)
        sink.clear()
        return commitBest(state, best)
    }

    /** Incremental scoring of structured [proposed] moves: pick the most objective-improving
     *  feasibility-preserving move via netDelta + objectiveDelta. Returns the best move, or the
     *  best found so far on cancellation. */
    private fun bestStructuredIncremental(
        state: LocalSearchState,
        objective: Objective,
        proposed: List<Move>,
        poll: IntArray,
        cancellation: Cancellation,
    ): Move? {
        val baseCost = state.cost
        var bestDelta = 0.0
        var bestMove: Move? = null
        for (move in proposed) {
            if (pollCancel(poll, cancellation)) return bestMove
            if (baseCost + state.netDelta(move) != 0L) continue
            val delta = state.objectiveDelta(objective, move) ?: continue
            if (delta < bestDelta) {
                bestDelta = delta
                bestMove = move
            }
        }
        return bestMove
    }

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

            is Move.Compound -> {
                // Revert in reverse order so each part sees the post-apply state of the
                // ones after it (mirror of the forward `apply` order).
                for (part in move.parts.reversed()) revertMove(state, part, baselineSnap)
            }
        }
    }

    /**
     * Bounded pair-swap descent step on the objective. Considers up to [budget] swap
     * candidates: pairs of bool vars with opposite current values (one true, one false →
     * flip both, preserving sum-count constraints) and pairs of int vars (swap values).
     * Returns `true` and commits if a swap strictly improves the objective while keeping
     * `cost == 0`. Returns `false` if no improving swap is found within the budget.
     *
     * The pair set is large — Θ(n²) for n vars — so the search is randomized: each call
     * draws fresh random pairs from the RNG until budget exhausted. This is best-fit-ish
     * not best-improvement, which suits LS where one good step is more valuable than
     * exhaustive comparison.
     */
    private fun pairSwapStep(
        state: LocalSearchState,
        objective: Objective,
        budget: Int,
        cancellation: Cancellation,
    ): Boolean {
        val baseCost = state.cost
        val poll = IntArray(1)
        var tried = 0
        // Bool-pair swaps: pick a true var and a false var, flip both.
        if (problem.numBoolVars >= 2) {
            while (tried < budget) {
                if (pollCancel(poll, cancellation)) return false
                tried++
                val swap = PairSwap.drawBoolSwap(state) ?: continue
                // Score the joint swap without committing: netDelta for feasibility, objectiveDelta
                // for the improvement test.
                if (baseCost + state.netDelta(swap) == 0L) {
                    val od = state.objectiveDelta(objective, swap)
                    if (od != null && od < 0.0) {
                        state.apply(swap)
                        return true
                    }
                }
            }
        }
        // Int-pair swaps: pick two int vars with different values whose values fit in the
        // other's domain; swap them.
        if (problem.numIntVars >= 2) {
            tried = 0
            while (tried < budget) {
                if (pollCancel(poll, cancellation)) return false
                tried++
                val swap = PairSwap.drawIntSwap(state) ?: continue
                if (baseCost + state.netDelta(swap) == 0L) {
                    val od = state.objectiveDelta(objective, swap)
                    if (od != null && od < 0.0) {
                        state.apply(swap)
                        return true
                    }
                }
            }
        }
        return false
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

        /** Feasibility-fight moves per round of adaptive-schedule feedback. A round is the batch over
         *  which an adaptive [com.eignex.klause.localsearch.schedule.Schedule] retunes; sized
         *  so the acceptance-ratio estimate is stable yet the schedule still reacts many times. */
        const val ROUND_FEEDBACK_STEPS: Int = 1024
    }
}
