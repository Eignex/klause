package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.propagation.PropagationResult
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
 *    duplicates may appear. Use [com.eignex.klause.solver.backtrack.BacktrackSolver] when
 *    true without-replacement enumeration is required.
 */
class LocalSearchSolver(
    override val problem: Problem,
    // SOTA defaults (2026): adaptive probSAT with the OrImproving tabu aspiration. probSAT's
    // continuous-weighted candidate distribution handles mixed-degree factor problems more
    // gracefully than WalkSat's binary noise/greedy split; the adaptive controller removes
    // the cb-tuning burden by widening the distribution during stalls and re-sharpening on
    // progress (Hoos 2002, Balint-Schöning 2012). Tabu aspiration admits individually
    // improving moves that would otherwise be blocked by the tenure window.
    /** Strategy used during the satisfy phase. */
    val strategy: Strategy = ProbSat.adaptive(
        tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
    ),
    /** Strategy used during the feasibility-fight phase of [minimize]. `null` (default)
     *  reuses [strategy], preserving backward-compat — users who override [strategy] for
     *  optimization workloads get that same strategy in minimize without re-passing it.
     *  Override explicitly to decouple the satisfy-mode and minimize-mode strategies; the
     *  common case is satisfy-mode [ProbSat.adaptive] + minimize-mode [Cbls] for decomposed CP
     *  problems, where CBLS's global weighted-violation gradient descends the objective on
     *  instances where probSAT alone plateaus. */
    val optimizeStrategy: Strategy? = null,
    /** Restart policy controlling diversification. */
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
    /** Cap on pair-swap candidates considered before the objective descent gives up at a
     *  single-flip local minimum. Pair swaps escape plateaus where every single flip
     *  breaks feasibility but a coordinated 2-flip preserves it (common in
     *  binary-decision optimization like knapsack / packing). 0 disables pair-swap. */
    val pairSwapBudget: Int = 256,
    /** When true (default), restarts run a greedy-repair pass after randomizing so the
     *  search starts closer to feasibility. Large instances of decomposed CP problems
     *  routinely produce thousands of violations from a random start; the greedy pass
     *  walks vars in a randomized order and picks the value that minimizes immediate
     *  violation contribution. Idempotent and bounded by [Problem.numBoolVars +
     *  numIntVars]. */
    val greedyRepairOnRestart: Boolean = true,
    /** Optional definitional sweep (see [com.eignex.klause.solver.DefinitionalSweep]): after
     *  every restart's randomization, defined (aux) vars are *evaluated* bottom-up from the
     *  free decision vars instead of left random — decomposed models start each restart at
     *  the "only real constraints violated" frontier rather than spending millions of flips
     *  hand-repairing definitional channels. Wired by FlatZinc-facing callers from
     *  `FlatZincProgram.definitionalSweep`; null = behavior unchanged. */
    val definitionalSweep: DefinitionalSweep? = null,
    /** Per-move one-way invariants (issue #153, opt-in): maintain [definitionalSweep]'s
     *  definitions incrementally after every applied move and exclude defined vars from move
     *  generation entirely — the move space shrinks to true decision variables. Requires
     *  [definitionalSweep]; the restart-time sweep stays active as the full (re)initializer. */
    val perMoveInvariants: Boolean = false,
) : Solver<LocalSearchParams>,
    Optimizer<LocalSearchParams> {

    /** The restart policy actually driven by the engine: when a [definitionalSweep] is
     *  present, every restart is followed by the sweep + a state recompute, so all restart
     *  call sites (satisfy loop, optimize loop, streaming) get swept uniformly. */
    private val restarts: RestartPolicy = if (definitionalSweep == null) {
        restartPolicy
    } else {
        object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
                restartPolicy.shouldRestart(stepsSinceLastRestart)

            override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) =
                restartPolicy.onLocalOptimum(state, sample, objective)

            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                restartPolicy.restart(state, bestSoFar)
                definitionalSweep.sweep(
                    state.assignment,
                    problem.intDomains,
                    problem.factors,
                ) { state.assumptions.isFrozenBool(it) }
                state.recompute()
            }
        }
    }

    /** Install the per-move invariant index on a fresh state when enabled (issue #153). */
    private fun installInvariants(state: LocalSearchState) {
        if (!perMoveInvariants) return
        val sweep = definitionalSweep ?: return
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
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
        val sample = sampleInternal(params, eff, warm)
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

    private fun sampleInternal(params: LocalSearchParams, eff: Assumptions, warm: WarmState?): Sample? =
        streamImpl(params, eff, warm).firstOrNull()

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
        // Wall-time-only stats, matching solveInternal's level of detail for this backend.
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
    ): Sequence<Sample> {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        // Tighten with the cross-backend instruction budget when set.
        val maxFlips = minOf(params.maxFlips, params.maxInstructions ?: Long.MAX_VALUE)
        return sequence {
            val state = LocalSearchState(problem, Random(seed), effectiveAssumptions)
            state.violationSoftCap = params.violationSoftCap
            installInvariants(state)
            warm?.applyTo(state)
            // Streaming has no notion of "best so far" to anchor an adaptive restart
            // around — pass null so policies that need a sample fall back to a fresh
            // random restart.
            restarts.restart(state, bestSoFar = null)
            var flipsSinceRestart = 0
            // Best-cost-so-far snapshot (even while infeasible): an [IteratedLocalSearchRestart]
            // perturbs from this instead of full-randomising, so a long descent/plateau-escape
            // accumulates progress across restarts rather than resetting each cadence window.
            // [FixedCadenceRestart] ignores it (full random), so threading it is a no-op there.
            var bestCost = state.cost
            var bestSnap: Sample? = state.assignment.snapshot()
            // Bound per yield, not per session: when [maxFlips] elapses without producing a
            // fresh sample, we've effectively exhausted the search neighbourhood — end the
            // sequence.
            var flipsSinceYield = 0L
            var cancelCountdown = 0

            try {
                while (flipsSinceYield < maxFlips) {
                    if (cancelCountdown-- <= 0) {
                        if (params.cancellation()) return@sequence
                        cancelCountdown = CANCEL_CHECK_INTERVAL
                    }
                    if (state.cost == 0L) {
                        val snap = state.assignment.snapshot()
                        // Sync warm state on every yield so streaming consumers (which
                        // typically take just one or a few samples and never drain the
                        // sequence) still see captured weights.
                        warm?.captureFrom(state)
                        yield(snap)
                        flipsSinceYield = 0
                        restarts.restart(state, bestSoFar = null)
                        bestCost = state.cost
                        bestSnap = state.assignment.snapshot()
                        flipsSinceRestart = 0
                        continue
                    }
                    if (restarts.shouldRestart(flipsSinceRestart)) {
                        restarts.restart(state, bestSoFar = bestSnap)
                        flipsSinceRestart = 0
                        continue
                    }
                    val move = strategy.pickMove(state)
                    if (move == null) {
                        restarts.restart(state, bestSoFar = bestSnap)
                        flipsSinceRestart = 0
                        continue
                    }
                    state.apply(move)
                    if (state.cost < bestCost) {
                        bestCost = state.cost
                        bestSnap = state.assignment.snapshot()
                    }
                    flipsSinceRestart++
                    flipsSinceYield++
                }
            } finally {
                // Sync learned weights back into warm state when the loop exits naturally
                // or when the consumer cancels (sequence builder closes the coroutine). On
                // abandoned sequences this may not fire; that's accepted loss.
                warm?.captureFrom(state)
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
        installInvariants(state)
        warm?.applyTo(state)
        // Plumb shaping into the state so strategies (e.g. WalkSat) consulting
        // shapedBreakScore see the objective during pre-feasibility moves too. Only
        // [CostShaping.Linear] contributes a non-zero lambda; FeasibilityFirst leaves
        // the field at 0.0 so behavior is identical to the no-shaping path.
        state.objective = objective
        state.shapingLambda = (params.costShaping as? CostShaping.Linear)?.lambda ?: 0.0
        // Warm-start the descent from a caller-supplied assignment (e.g. a CP-found feasible
        // point) instead of a random restart, when one is provided and arity-compatible. Null
        // by default → pure random restart (no CP dependency). See
        // [LocalSearchParams.initialAssignment].
        val seeded = params.initialAssignment?.let { seedFrom(state, it) } ?: false
        // No bestSample yet — first restart is always full random (unless we seeded above).
        if (!seeded) restarts.restart(state, bestSoFar = null)
        // Greedy-repair is gated on problem size: on tiny problems the LS engine reaches
        // feasibility in microseconds and the repair pass is pure overhead; the gating
        // also avoids changing observable convergence on the existing small unit tests.
        // Threshold is ~one cache line of var ids — pragmatically, anything above 32 vars
        // benefits from the pre-seed.
        val largeEnoughForGreedy = (problem.numBoolVars + problem.numIntVars) >= 32
        if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        // Best-cost (still-infeasible) snapshot for ILS perturbation *before* feasibility is
        // reached: when [bestSample] is null an [IteratedLocalSearchRestart] perturbs from this
        // instead of full-randomising, so a long feasibility fight accumulates progress.
        var bestCostInfeasible: Long = Long.MAX_VALUE
        var bestCostSnap: Sample? = null
        var flipsSinceRestart = 0
        var totalFlips = 0L
        val maxFlips = minOf(params.maxFlips, params.maxInstructions ?: Long.MAX_VALUE)
        val shaping = params.costShaping
        var cancelled = false

        // Each restart counts as one unit of work against [maxFlips]. Otherwise a
        // degenerate objective (e.g. all-zero) on a constraint-free problem would
        // produce an infinite loop: cost stays at 0, greedy descent never improves,
        // and the restart path otherwise wouldn't bump [totalFlips].
        // Phase strategy: when [optimizeStrategy] is feasibility-aware (gates objective
        // behind cost==0) it drives both phases — CBLS's pure-netDelta scoring at
        // infeasibility beats ProbSat at multi-flip-cascade problems, and its
        // weight-bumping helps escape SAT-shape local minima too. Fall back to phase
        // split (strategy for satisfy, optimizeStrategy for descent) for non-unified
        // strategies that bail at feasibility (DDFW/ProbSat).
        val descentStrategy = optimizeStrategy
        val unified = descentStrategy is Cbls
        var cancelCountdown = 0
        while (totalFlips < maxFlips) {
            if (cancelCountdown-- <= 0) {
                if (params.cancellation()) {
                    cancelled = true
                    break
                }
                cancelCountdown = CANCEL_CHECK_INTERVAL
            }
            if (state.cost == 0L) {
                // Each descent step below is O(numVars); the once-per-CANCEL_CHECK_INTERVAL
                // throttle above is too coarse to keep the optimize phase deadline-responsive
                // (a step's inner poll bails the scan, but without this the loop could still
                // spin many bounded steps before the throttle fires). One extra cancellation
                // poll per descent step is negligible against the step's own cost (#94).
                if (params.cancellation()) {
                    cancelled = true
                    break
                }
                // Score the current feasible assignment, record if best.
                val snap = state.assignment.snapshot()
                val obj = objective.evaluate(snap)
                if (obj < bestObj) {
                    bestObj = obj
                    bestSample = snap
                    params.onEvent?.invoke(SearchEvent.Incumbent(obj))
                    // Yield each strict improvement as the inner loop discovers it.
                    yield(MinimizeResult.BestFound(snap, obj, TerminationReason.BudgetExhausted))
                }
                // Descent options, in order of "informedness". Each step gets one chance
                // before we fall through to the next; stalling all of them triggers a
                // restart. Order matters: greedy single-flip is cheapest, CBLS adds
                // cross-factor weighted scoring, structured-move uses factor-aware swaps,
                // pair-swap is the random fallback.
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
                // CBLS descent: ask the optimizeStrategy for an objective-improving move.
                // CBLS's pickMove at feasibility generates seed-objective moves +
                // satisfied-factor structured moves and scores them by `weightedNetDelta
                // + λ·objectiveDelta`. SAT-style strategies (ProbSat/DDFW) return null at
                // feasibility — skip them here.
                if (descentStrategy != null) {
                    val m = descentStrategy.pickMove(state)
                    if (m != null) {
                        // Only commit if the move keeps feasibility AND improves objective —
                        // CBLS may propose moves that break feasibility (e.g. a true→false
                        // flip that opens slack) which we don't want during the gated
                        // descent phase.
                        val savedSnap = state.assignment.snapshot()
                        val baseObj = objective.evaluate(savedSnap)
                        state.apply(m)
                        if (state.cost == 0L && objective.evaluate(state.assignment.snapshot()) < baseObj) {
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
                restarts.onLocalOptimum(state, snap, obj)
                restarts.restart(state, bestSample)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            if (restarts.shouldRestart(flipsSinceRestart)) {
                restarts.restart(state, bestSample ?: bestCostSnap)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            // Pre-feasibility: drive through the unified strategy when one is configured,
            // else use the satisfy-mode strategy. CBLS (when unified) scores by
            // weightedNetDelta only at cost>0, which gives it equivalent feasibility-fight
            // behavior to ProbSat plus better multi-flip handling.
            val move = if (unified) descentStrategy.pickMove(state) else strategy.pickMove(state)
            if (move == null) {
                restarts.restart(state, bestSample ?: bestCostSnap)
                if (greedyRepairOnRestart && largeEnoughForGreedy) greedyRepairPass(state)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            state.apply(move)
            if (state.cost in 1 until bestCostInfeasible) {
                bestCostInfeasible = state.cost
                bestCostSnap = state.assignment.snapshot()
            }
            flipsSinceRestart++
            totalFlips++
        }
        warm?.captureFrom(state)
        val reason = if (cancelled) TerminationReason.Cancelled else TerminationReason.BudgetExhausted
        sink.stop()
        sink.timedOut = reason == TerminationReason.BudgetExhausted
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
        // Respect caller pins exactly as restart() does, so a seed can't violate assumptions.
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
        // Score each candidate via netDelta (post-move cost, no commit) + objectiveDelta
        // (O(arity)), so no snapshot/evaluate per candidate — only on commit. Every objective
        // that can reach the descent is incremental ([LinearObjective] or the caller's
        // [LocalSearchParams.lsObjective] gradient view), so there is no evaluate fallback.
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
     *  single-element [counter] box so the count carries across calls within one descent
     *  scan. Returns true when the candidate loop should abort. Keeps the per-var descent
     *  loops deadline-responsive (issue #94) — a single step is O(numVars) and would
     *  otherwise run well past the deadline before the outer loop's check. */
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
        // (baseCost + netDelta, baselineObj + objectiveDelta) — no per-candidate
        // snapshot/evaluate, and no apply/revert (objectiveDelta reads the live assignment).
        // Every reachable objective is incremental; there is no evaluate fallback.
        val baseCost = state.cost
        val baselineObj = objective.evaluate(state.assignment.snapshot())
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
     * Greedy-repair pass over [state] right after a restart. Walks vars in randomized
     * order; for each, picks the value (bool: true/false, int: any value in the domain
     * for ≤16-size domains, otherwise sampled) that minimizes the current `state.cost`.
     * Ties broken by keeping the current value. Single forward pass — no fixed-point
     * loop. Idempotent on already-feasible states.
     *
     * The point isn't to reach feasibility (LS strategies handle that) but to start the
     * search from a low-violation pose so the feasibility-fight phase has fewer hard
     * constraints to chase. On large decomposed instances (e.g. 2DBinPacking_100) a
     * random start has 1000+ violations; this pass typically drops it by 30–60% before
     * the main loop runs.
     */
    private fun greedyRepairPass(state: LocalSearchState) {
        val varCount = problem.numBoolVars + problem.numIntVars
        if (varCount == 0) return
        val order = IntArray(varCount) { it }
        // Fisher-Yates shuffle using the state's RNG so the pass is deterministic for a
        // given seed.
        for (i in order.size - 1 downTo 1) {
            val j = state.rng.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        for (v in order) {
            if (v < problem.numBoolVars) {
                val boolId = v
                if (state.assumptions.isFrozenBool(boolId)) continue
                val baselineCost = state.cost
                state.apply(Move.BoolFlip(boolId))
                if (state.cost > baselineCost) state.apply(Move.BoolFlip(boolId))
            } else {
                val intId = v - problem.numBoolVars
                if (state.assumptions.isFrozenInt(intId)) continue
                val d = problem.intDomains[intId]
                val cur = state.assignment.intValue(intId)
                if (d.size <= 1) continue
                // For tiny domains (≤16 values) sweep all; for larger domains sample up
                // to 16 candidates to bound the per-pass cost at O(numVars × 16).
                val maxTries = 16
                var bestCost = state.cost
                var bestVal = cur
                if (d.size <= maxTries) {
                    for (idx in 0 until d.size) {
                        val candidate = d.valueAt(idx)
                        if (candidate == cur) continue
                        state.apply(Move.IntSet(intId, candidate))
                        if (state.cost < bestCost) {
                            bestCost = state.cost
                            bestVal = candidate
                        }
                        state.apply(Move.IntSet(intId, cur))
                    }
                } else {
                    repeat(maxTries) {
                        val candidate = d.valueAt(state.rng.nextInt(d.size))
                        if (candidate == cur) return@repeat
                        state.apply(Move.IntSet(intId, candidate))
                        if (state.cost < bestCost) {
                            bestCost = state.cost
                            bestVal = candidate
                        }
                        state.apply(Move.IntSet(intId, cur))
                    }
                }
                if (bestVal != cur) state.apply(Move.IntSet(intId, bestVal))
            }
        }
        // Reset tabu / activity tracking so the main loop doesn't start with every var
        // freshly blocked by the repair pass's apply-then-revert churn.
        state.resetStepCounters()
    }

    /**
     * Factor-aware structured descent step. Collects [com.eignex.klause.solver.Factor.proposeStructuredMoves]
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
        for (fid in 0 until problem.numFactors) {
            val f = state.factors[fid]
            // Only consult factors that are currently satisfied. A violated factor would
            // propose repair moves (which run before objective descent) so we skip here.
            if (!f.isViolated(state, fid)) f.proposeStructuredMoves(state, fid, sink)
        }
        val proposed = sink.list
        if (proposed.isEmpty()) return false
        val poll = IntArray(1)
        val best = bestStructuredIncremental(state, objective, proposed, poll, cancellation)
        sink.clear()
        return commitBest(state, best)
    }

    /** Incremental scoring of structured [proposed] moves: pick the most objective-improving
     *  feasibility-preserving move via netDelta (post-move cost; the Compound path is
     *  apply/revert-backed and fully restores state) + objectiveDelta — no per-move
     *  snapshot/evaluate. Returns the best move (or the best found so far on cancellation). */
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
        val rng = state.rng
        val poll = IntArray(1)
        var tried = 0
        // Bool-pair swaps: pick a true var and a false var, flip both.
        val nBool = problem.numBoolVars
        if (nBool >= 2) {
            while (tried < budget) {
                if (pollCancel(poll, cancellation)) return false
                tried++
                val a = rng.nextInt(nBool)
                val b = rng.nextInt(nBool)
                if (a == b) continue
                if (state.assumptions.isFrozenBool(a) || state.assumptions.isFrozenBool(b)) continue
                val va = state.assignment.boolValue(a)
                val vb = state.assignment.boolValue(b)
                if (va == vb) continue
                // Score the joint swap without committing: netDelta (apply/revert-backed for
                // the Compound) for feasibility, objectiveDelta for the improvement test.
                val swap = Move.Compound(listOf(Move.BoolFlip(a), Move.BoolFlip(b)))
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
        val nInt = problem.numIntVars
        if (nInt >= 2) {
            tried = 0
            val intBudget = budget
            while (tried < intBudget) {
                if (pollCancel(poll, cancellation)) return false
                tried++
                val a = rng.nextInt(nInt)
                val b = rng.nextInt(nInt)
                if (a == b) continue
                if (state.assumptions.isFrozenInt(a) || state.assumptions.isFrozenInt(b)) continue
                val va = state.assignment.intValue(a)
                val vb = state.assignment.intValue(b)
                if (va == vb) continue
                if (vb !in problem.intDomains[a] || va !in problem.intDomains[b]) continue
                val swap = Move.Compound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
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

    private companion object {
        /** Polling interval for cooperative cancellation; see Cancellation.kt. */
        const val CANCEL_CHECK_INTERVAL: Int = 1024
    }
}
