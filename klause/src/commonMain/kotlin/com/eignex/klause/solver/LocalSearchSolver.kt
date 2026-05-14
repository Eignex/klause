package com.eignex.klause.solver

import com.eignex.klause.solver.strategy.Strategy
import com.eignex.klause.solver.strategy.WalkSat
import kotlin.random.Random

/**
 * Local-search [Solver] around a [Problem]. The solver itself only carries engine setup
 * (strategy, restart cadence). All per-draw state — RNG, assignment, factor payloads, the
 * dedup window — lives inside the per-call sequences so concurrent draws never share state.
 *
 * Three call kinds, each accepting a [LocalSearchParams]:
 *
 *  - [solve] — return a single [SolveResult]; LS never reports `Unsat`.
 *  - [sample] — *with replacement*. Independent draws; duplicates allowed.
 *  - [enumerate] — *without replacement*. Rolling-window dedup honouring
 *    `params.minHammingDistance` and `params.recentWindow`.
 */
class LocalSearchSolver(
    override val problem: Problem,
    val strategy: Strategy = WalkSat(),
    val restartPolicy: RestartPolicy = FixedCadenceRestart(),
) : Solver<LocalSearchParams>, Optimizer<LocalSearchParams> {

    override fun solve(params: LocalSearchParams): SolveResult = solveInternal(params, warm = null)

    override fun samples(params: LocalSearchParams): Sequence<Sample> = samplesInternal(params, warm = null)

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> = enumerateInternal(params, warm = null)

    internal fun solveInternal(params: LocalSearchParams, warm: WarmState?): SolveResult {
        val eff = effectiveAssumptions(params.assumptions) ?: return SolveResult.Unsat
        return sampleInternal(params, eff, warm)?.let(SolveResult::Sat) ?: SolveResult.Unknown
    }

    internal fun samplesInternal(params: LocalSearchParams, warm: WarmState?): Sequence<Sample> {
        val eff = effectiveAssumptions(params.assumptions) ?: return emptySequence()
        return streamImpl(params.copy(minHammingDistance = 0, recentWindow = 0), eff, warm)
    }

    internal fun enumerateInternal(params: LocalSearchParams, warm: WarmState?): Sequence<Sample> {
        val eff = effectiveAssumptions(params.assumptions) ?: return emptySequence()
        return streamImpl(params, eff, warm)
    }

    private fun sampleInternal(params: LocalSearchParams, eff: Assumptions, warm: WarmState?): Sample? =
        streamImpl(params.copy(minHammingDistance = 0, recentWindow = 0), eff, warm).firstOrNull()

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
            return if (baked.isEmpty) Assumptions.None
            else Assumptions(bools = baked.bools, ints = baked.ints)
        }
        return when (val r = problem.propagate(callAssumptions)) {
            is PropagationResult.Unsat -> null
            is PropagationResult.Implied -> {
                val mergedBools = HashMap<Int, Boolean>(callAssumptions.bools)
                mergedBools.putAll(r.bools)
                val mergedInts = HashMap<Int, Int>(callAssumptions.ints)
                mergedInts.putAll(r.ints)
                Assumptions(mergedBools, mergedInts)
            }
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
    override fun minimize(objective: Objective, params: LocalSearchParams): Sample? =
        minimizeInternal(objective, params, warm = null)

    internal fun minimizeInternal(objective: Objective, params: LocalSearchParams, warm: WarmState?): Sample? {
        val eff = effectiveAssumptions(params.assumptions) ?: return null
        return minimizeImpl(objective, params, eff, warm)
    }

    fun solve(): SolveResult = solve(LocalSearchParams())
    fun sample(): Sample? = sample(LocalSearchParams())
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())
    fun minimize(objective: Objective): Sample? = minimize(objective, LocalSearchParams())

    private fun streamImpl(
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState? = null,
    ): Sequence<Sample> {
        require(params.recentWindow >= 0) {
            "recentWindow must be non-negative, got ${params.recentWindow}"
        }
        val totalBits = problem.numBoolVars + problem.numIntVars
        require(params.minHammingDistance <= totalBits) {
            "minHammingDistance (${params.minHammingDistance}) exceeds the total variable " +
                "count ($totalBits); no two assignments can ever satisfy that distance."
        }
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val maxFlips = params.maxFlips
        val minHammingDistance = params.minHammingDistance
        val recentWindow = params.recentWindow
        return sequence {
            val state = SolverState(problem, Random(seed), effectiveAssumptions)
            warm?.applyTo(state)
            val window = ArrayDeque<Sample>()
            // Streaming has no notion of "best so far" to anchor an adaptive restart
            // around — pass null so policies that need a sample fall back to a fresh
            // random restart.
            restartPolicy.restart(state, bestSoFar = null)
            var flipsSinceRestart = 0
            // Bound per yield, not per session: when [maxFlips] elapses without producing a
            // fresh sample, we've effectively exhausted the search neighbourhood — end the
            // sequence rather than spinning forever rejecting via [farEnough].
            var flipsSinceYield = 0L

            try { while (flipsSinceYield < maxFlips) {
                if (state.cost == 0) {
                    val snap = state.assignment.snapshot()
                    if (farEnough(snap, window, minHammingDistance, recentWindow)) {
                        // Sync warm state on every yield so streaming consumers (which
                        // typically take just one or a few samples and never drain the
                        // sequence) still see captured weights.
                        warm?.captureFrom(state)
                        yield(snap)
                        if (recentWindow > 0) {
                            if (window.size >= recentWindow) window.removeFirst()
                            window.addLast(snap)
                        }
                        flipsSinceYield = 0
                    }
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
    private fun minimizeImpl(
        objective: Objective,
        params: LocalSearchParams,
        effectiveAssumptions: Assumptions,
        warm: WarmState? = null,
    ): Sample? {
        val totalBits = problem.numBoolVars + problem.numIntVars
        require(params.minHammingDistance <= totalBits) {
            "minHammingDistance (${params.minHammingDistance}) exceeds the total variable " +
                "count ($totalBits)"
        }
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = SolverState(problem, Random(seed), effectiveAssumptions)
        warm?.applyTo(state)
        // No bestSample yet — first restart is always full random.
        restartPolicy.restart(state, bestSoFar = null)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        var flipsSinceRestart = 0
        var totalFlips = 0L
        val maxFlips = params.maxFlips

        // Each restart counts as one unit of work against [maxFlips]. Otherwise a
        // degenerate objective (e.g. all-zero) on a constraint-free problem would
        // produce an infinite loop: cost stays at 0, greedy descent never improves,
        // and the restart path otherwise wouldn't bump [totalFlips].
        while (totalFlips < maxFlips) {
            if (state.cost == 0) {
                // Score the current feasible assignment, record if best.
                val snap = state.assignment.snapshot()
                val obj = objective.evaluate(snap)
                if (obj < bestObj) {
                    bestObj = obj
                    bestSample = snap
                }
                // Try to descend on the objective via a single feasibility-preserving move.
                val descended = greedyObjectiveStep(state, objective)
                if (descended) {
                    flipsSinceRestart++
                    totalFlips++
                    continue
                }
                // Local minimum on the objective — restart and try a different basin.
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
        return bestSample
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
    private fun greedyObjectiveStep(state: SolverState, objective: Objective): Boolean {
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

    private fun farEnough(
        candidate: Sample,
        window: ArrayDeque<Sample>,
        minDistance: Int,
        windowSize: Int,
    ): Boolean {
        if (minDistance <= 0 || windowSize == 0) return true
        for (p in window) {
            if (hammingDistance(candidate, p) < minDistance) return false
        }
        return true
    }

    private fun hammingDistance(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }
}
