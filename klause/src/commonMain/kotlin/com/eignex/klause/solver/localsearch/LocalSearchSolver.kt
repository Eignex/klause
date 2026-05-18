package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SampleResult
import com.eignex.klause.solver.TerminationReason
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.propagation.PropagationResult

import com.eignex.klause.solver.localsearch.strategy.AdaptiveProbSat
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
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
    val strategy: Strategy = AdaptiveProbSat(
        tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving),
    ),
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
) : Solver<LocalSearchParams>, Optimizer<LocalSearchParams> {

    override fun solve(params: LocalSearchParams): SolveResult = solveInternal(params, warm = null)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = samplesInternal(params, warm = null)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> = enumerateInternal(params, warm = null)

    /** Return a [LocalSearchSession] that persists DDFW-style factor weights across
     *  calls and maintains an assumption stack. Backend-specific override of
     *  [Solver.session]'s default `StatelessSession`. */
    override fun session(): LocalSearchSession = LocalSearchSession(this)

    internal fun solveInternal(params: LocalSearchParams, warm: WarmState?): SolveResult {
        val eff = effectiveAssumptions(params.assumptions) ?: return SolveResult.Unsat()
        return sampleInternal(params, eff, warm)?.let(SolveResult::Sat)
            ?: SolveResult.Unknown(TerminationReason.BudgetExhausted)
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
     * Specialises on [LinearObjective] for an O(arity) per-move delta. Other [Objective]
     * subtypes fall back to a full [Objective.evaluate] re-score per candidate move, which
     * is correct but slow.
     */
    override fun minimize(objective: Objective, params: LocalSearchParams): MinimizeResult =
        improvementsInternal(objective, params, warm = null).last()

    override fun improvements(
        objective: Objective,
        params: LocalSearchParams,
    ): Sequence<MinimizeResult> = improvementsInternal(objective, params, warm = null)

    /**
     * Internal minimize entry point. Local search is **incomplete**: it never proves
     * optimality or infeasibility. So the verdict is always either
     * [MinimizeResult.BestFound] (a feasible was reached) or [MinimizeResult.Unknown]
     * (budget gone before feasibility). Bake-time-Unsat is the one case we can prove
     * Infeasible — propagation derived it before LS started.
     */
    internal fun minimizeInternal(
        objective: Objective,
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
        objective: Objective,
        params: LocalSearchParams,
        warm: WarmState?,
    ): Sequence<MinimizeResult> = sequence {
        val eff = effectiveAssumptions(params.assumptions)
        if (eff == null) {
            yield(MinimizeResult.Infeasible())
            return@sequence
        }
        runMinimizeStream(objective, params, eff, warm)
    }

    fun solve(): SolveResult = solve(LocalSearchParams())
    fun sample(): SampleResult = sample(LocalSearchParams())
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())
    fun minimize(objective: Objective): MinimizeResult =
        minimize(objective, LocalSearchParams())

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
            warm?.applyTo(state)
            // Streaming has no notion of "best so far" to anchor an adaptive restart
            // around — pass null so policies that need a sample fall back to a fresh
            // random restart.
            restartPolicy.restart(state, bestSoFar = null)
            var flipsSinceRestart = 0
            // Bound per yield, not per session: when [maxFlips] elapses without producing a
            // fresh sample, we've effectively exhausted the search neighbourhood — end the
            // sequence.
            var flipsSinceYield = 0L
            var cancelCountdown = 0

            try { while (flipsSinceYield < maxFlips) {
                if (cancelCountdown-- <= 0) {
                    if (params.cancellation()) return@sequence
                    cancelCountdown = CANCEL_CHECK_INTERVAL
                }
                if (state.cost == 0) {
                    val snap = state.assignment.snapshot()
                    // Sync warm state on every yield so streaming consumers (which
                    // typically take just one or a few samples and never drain the
                    // sequence) still see captured weights.
                    warm?.captureFrom(state)
                    yield(snap)
                    flipsSinceYield = 0
                    restartPolicy.restart(state, bestSoFar = null)
                    flipsSinceRestart = 0
                    continue
                }
                if (restartPolicy.shouldRestart(flipsSinceRestart)) {
                    restartPolicy.restart(state, bestSoFar = null)
                    flipsSinceRestart = 0
                    continue
                }
                val move = strategy.pickMove(state)
                if (move == null) {
                    restartPolicy.restart(state, bestSoFar = null)
                    flipsSinceRestart = 0
                    continue
                }
                state.apply(move)
                flipsSinceRestart++
                flipsSinceYield++
            } } finally {
                // Sync learned weights back into warm state when the loop exits naturally
                // or when the consumer cancels (sequence builder closes the coroutine). On
                // abandoned sequences this may not fire; that's accepted loss.
                warm?.captureFrom(state)
            }
        }
    }

    /**
     * Two-phase search per restart attempt: WalkSat-style fight to feasibility, then a
     * greedy descent on the objective restricted to feasibility-preserving moves. When the
     * descent reaches a local minimum (no neighbour both keeps `cost == 0` and lowers
     * the objective), restart and try again. Best-feasible-objective state lives across
     * restarts so we monotonically improve.
     */
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
    ) {
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = LocalSearchState(problem, Random(seed), effectiveAssumptions)
        warm?.applyTo(state)
        // Plumb shaping into the state so strategies (e.g. WalkSat) consulting
        // shapedBreakScore see the objective during pre-feasibility moves too. Only
        // [CostShaping.Linear] contributes a non-zero lambda; FeasibilityFirst leaves
        // the field at 0.0 so behavior is identical to the no-shaping path.
        state.objective = objective
        state.shapingLambda = (params.costShaping as? CostShaping.Linear)?.lambda ?: 0.0
        // No bestSample yet — first restart is always full random.
        restartPolicy.restart(state, bestSoFar = null)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        var flipsSinceRestart = 0
        var totalFlips = 0L
        val maxFlips = minOf(params.maxFlips, params.maxInstructions ?: Long.MAX_VALUE)
        val shaping = params.costShaping
        var cancelled = false

        // Each restart counts as one unit of work against [maxFlips]. Otherwise a
        // degenerate objective (e.g. all-zero) on a constraint-free problem would
        // produce an infinite loop: cost stays at 0, greedy descent never improves,
        // and the restart path otherwise wouldn't bump [totalFlips].
        var cancelCountdown = 0
        while (totalFlips < maxFlips) {
            if (cancelCountdown-- <= 0) {
                if (params.cancellation()) { cancelled = true; break }
                cancelCountdown = CANCEL_CHECK_INTERVAL
            }
            if (state.cost == 0) {
                // Score the current feasible assignment, record if best.
                val snap = state.assignment.snapshot()
                val obj = objective.evaluate(snap)
                if (obj < bestObj) {
                    bestObj = obj
                    bestSample = snap
                    // Yield each strict improvement as the inner loop discovers it.
                    yield(MinimizeResult.BestFound(snap, obj, TerminationReason.BudgetExhausted))
                }
                // Try to descend via a single move. Under [CostShaping.FeasibilityFirst]
                // this stays inside the feasible region; under a linear shaping it may
                // temporarily step into infeasibility if the shaped score improves —
                // the strategy loop below then drives back to feasibility.
                val descended = if (shaping.feasibilityGated) {
                    greedyObjectiveStep(state, objective)
                } else {
                    shapedDescentStep(state, objective, shaping)
                }
                if (descended) {
                    flipsSinceRestart++
                    totalFlips++
                    continue
                }
                // Local minimum on the (shaped) objective — give ILS-style policies a
                // chance to update their incumbent before we restart.
                restartPolicy.onLocalOptimum(state, snap, obj)
                restartPolicy.restart(state, bestSample)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            if (restartPolicy.shouldRestart(flipsSinceRestart)) {
                restartPolicy.restart(state, bestSample)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            val move = strategy.pickMove(state)
            if (move == null) {
                restartPolicy.restart(state, bestSample)
                flipsSinceRestart = 0
                totalFlips++
                continue
            }
            state.apply(move)
            flipsSinceRestart++
            totalFlips++
        }
        warm?.captureFrom(state)
        val reason = if (cancelled) TerminationReason.Cancelled else TerminationReason.BudgetExhausted
        yield(
            if (bestSample != null) MinimizeResult.BestFound(bestSample, bestObj, reason)
            else MinimizeResult.Unknown(reason)
        )
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
    private fun greedyObjectiveStep(state: LocalSearchState, objective: Objective): Boolean {
        val baselineSnap = state.assignment.snapshot()
        val baselineObj = objective.evaluate(baselineSnap)
        var bestDelta = 0.0
        var bestMove: Move? = null

        for (b in 0 until problem.numBoolVars) {
            if (state.assumptions.isFrozenBool(b)) continue
            state.apply(Move.BoolFlip(b))
            if (state.cost == 0) {
                val obj = objective.evaluate(state.assignment.snapshot())
                val delta = obj - baselineObj
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestMove = Move.BoolFlip(b)
                }
            }
            state.apply(Move.BoolFlip(b)) // revert
        }

        for (i in 0 until problem.numIntVars) {
            if (state.assumptions.isFrozenInt(i)) continue
            val cur = state.assignment.intValue(i)
            val d = problem.intDomains[i]
            for (target in intArrayOf(cur - 1, cur + 1)) {
                if (target !in d.min..d.max) continue
                state.apply(Move.IntSet(i, target))
                if (state.cost == 0) {
                    val obj = objective.evaluate(state.assignment.snapshot())
                    val delta = obj - baselineObj
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestMove = Move.IntSet(i, target)
                    }
                }
                state.apply(Move.IntSet(i, cur)) // revert
            }
        }

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
    ): Boolean {
        val baselineSnap = state.assignment.snapshot()
        val baselineObj = objective.evaluate(baselineSnap)
        val baselineShaped = shaping.shape(state.cost, baselineObj)
        var bestShaped = baselineShaped
        var bestMove: Move? = null

        for (b in 0 until problem.numBoolVars) {
            if (state.assumptions.isFrozenBool(b)) continue
            state.apply(Move.BoolFlip(b))
            val obj = objective.evaluate(state.assignment.snapshot())
            val shaped = shaping.shape(state.cost, obj)
            if (shaped < bestShaped) {
                bestShaped = shaped
                bestMove = Move.BoolFlip(b)
            }
            state.apply(Move.BoolFlip(b)) // revert
        }

        for (i in 0 until problem.numIntVars) {
            if (state.assumptions.isFrozenInt(i)) continue
            val cur = state.assignment.intValue(i)
            val d = problem.intDomains[i]
            for (target in intArrayOf(cur - 1, cur + 1)) {
                if (target !in d.min..d.max) continue
                state.apply(Move.IntSet(i, target))
                val obj = objective.evaluate(state.assignment.snapshot())
                val shaped = shaping.shape(state.cost, obj)
                if (shaped < bestShaped) {
                    bestShaped = shaped
                    bestMove = Move.IntSet(i, target)
                }
                state.apply(Move.IntSet(i, cur)) // revert
            }
        }

        if (bestMove == null) return false
        state.apply(bestMove)
        return true
    }

    private companion object {
        /** Polling interval for cooperative cancellation; see Cancellation.kt. */
        const val CANCEL_CHECK_INTERVAL: Int = 1024
    }
}
