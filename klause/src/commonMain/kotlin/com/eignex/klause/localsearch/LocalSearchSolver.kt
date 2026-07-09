package com.eignex.klause.localsearch

import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.GreedyInit
import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundAccumulator
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
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
    /** SourceDrivenStrategy for the satisfy phase (and, when [optimizeStrategy] is null, the minimize
     *  phase too, via its [SourceDrivenStrategy.feasibleDescent]). Default is [Cbls] — its
     *  [FeasibleDescent.SelfOwned] descent (greedy over its objective / structured / pair-swap sources)
     *  runs the objective optimize, so a bare `LocalSearchSolver(problem).minimize(...)` optimizes with
     *  no extra wiring. Override for a
     *  different arm — `ProbSat.adaptive()` for a boolean core (ratcheted on a COP by the portfolio),
     *  `SimulatedAnnealing.optimizer(...)` to anneal. */
    val strategy: SourceDrivenStrategy = Cbls(),
    /** SourceDrivenStrategy for the feasibility-fight phase of [minimize]. `null` reuses [strategy].
     *  Override to decouple satisfy-mode and minimize-mode strategies; e.g. satisfy [ProbSat.adaptive] +
     *  minimize [Cbls] for decomposed CP problems, where CBLS's weighted-violation gradient descends the
     *  objective on instances where probSAT plateaus. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
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

    /** Objective-as-constraint ratchet handle (opt-in). Set non-null only for an arm whose [problem]
     *  carries an [com.eignex.klause.factor.objective.ObjectiveBoundFactor] sharing this bound: on each
     *  feasible incumbent the minimize loop tightens it below the incumbent, so the objective slack
     *  re-enters the violation set and the feasibility fight repairs it — the SAT→optimization ratchet
     *  for the violation-native arms (probSAT / WalkSAT). Null leaves objective handling unchanged. */
    internal var objectiveBound: MutableObjectiveBound? = null

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
            // A restart policy instance can be reused across solves (a tuning campaign runs one
            // recipe over many problems); clear its per-solve state so a stale incumbent — possibly
            // of a different variable arity — can't leak in and be indexed against this problem.
            // ScheduleBundle leaves restart reset to the engine; reset the underlying policy, not
            // the `restarts` wrapper (whose reset is the interface no-op).
            configuredRestart.reset()
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
        var cancelled = false

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

        // Restart from [anchor] and re-run the greedy repair sweep under the same gate as the
        // initial restart above — the pairing every restart site must preserve. Counters and
        // round bookkeeping stay at the call sites so the hot-loop locals aren't captured.
        fun restartAndRepair(anchor: Sample?) {
            restarts.restart(state, anchor)
            if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
        }

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
                        restartAndRepair(bestSample)
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
                        restartAndRepair(bestSample)
                        stallCount++
                        restartCount++
                        flipsSinceRestart = 0
                        totalFlips++
                        continue
                    }
                }
            }
            if (restarts.shouldRestart(flipsSinceRestart)) {
                restartAndRepair(bestSample ?: bestCostSnap)
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
                restartAndRepair(bestSample ?: bestCostSnap)
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

        /** Feasibility-fight moves per round of adaptive-schedule feedback. A round is the batch over
         *  which an adaptive [com.eignex.klause.localsearch.schedule.Schedule] retunes; sized
         *  so the acceptance-ratio estimate is stable yet the schedule still reacts many times. */
        const val ROUND_FEEDBACK_STEPS: Int = 1024
    }
}
