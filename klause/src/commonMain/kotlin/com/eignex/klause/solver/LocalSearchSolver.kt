package com.eignex.klause.solver

import com.eignex.klause.solver.strategy.Strategy
import com.eignex.klause.solver.strategy.WalkSat
import kotlin.random.Random

/**
 * Local-search [Sampler] around a [Problem]. The solver itself only carries engine setup
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
) : Sampler<LocalSearchParams>, Optimizer<LocalSearchParams> {

    override fun solve(params: LocalSearchParams): SolveResult =
        sample(params)?.let(SolveResult::Sat) ?: SolveResult.Unknown

    override fun samples(params: LocalSearchParams): Sequence<Sample> =
        streamImpl(params.copy(minHammingDistance = 0, recentWindow = 0))

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> =
        streamImpl(params)

    /**
     * Best-effort linear-objective minimisation under hard constraints. Reaches feasibility
     * via the configured [strategy] (WalkSat/probSAT-style), then descends on the objective
     * by greedy single-flip / single-set moves that keep `hardCost == 0`. Whenever the
     * descent stalls or the budget per attempt elapses, the search restarts with a fresh
     * randomized assignment; the best feasible objective seen across all attempts is
     * returned.
     *
     * Specialises on [LinearObjective] for an O(arity) per-move delta. Other [Objective]
     * subtypes fall back to a full [Objective.evaluate] re-score per candidate move, which
     * is correct but slow.
     */
    override fun minimize(objective: Objective, params: LocalSearchParams): Sample? =
        minimizeImpl(objective, params)

    fun solve(): SolveResult = solve(LocalSearchParams())
    fun sample(): Sample? = sample(LocalSearchParams())
    fun samples(): Sequence<Sample> = samples(LocalSearchParams())
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())
    fun minimize(objective: Objective): Sample? = minimize(objective, LocalSearchParams())

    private fun streamImpl(params: LocalSearchParams): Sequence<Sample> {
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
            val state = SolverState(problem, Random(seed))
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

            while (flipsSinceYield < maxFlips) {
                if (state.hardCost == 0) {
                    val snap = state.assignment.snapshot()
                    if (farEnough(snap, window, minHammingDistance, recentWindow)) {
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
            }
        }
    }

    /**
     * Two-phase search per restart attempt: WalkSat-style fight to feasibility, then a
     * greedy descent on the objective restricted to feasibility-preserving moves. When the
     * descent reaches a local minimum (no neighbour both keeps `hardCost == 0` and lowers
     * the objective), restart and try again. Best-feasible-objective state lives across
     * restarts so we monotonically improve.
     */
    private fun minimizeImpl(objective: Objective, params: LocalSearchParams): Sample? {
        val totalBits = problem.numBoolVars + problem.numIntVars
        require(params.minHammingDistance <= totalBits) {
            "minHammingDistance (${params.minHammingDistance}) exceeds the total variable " +
                "count ($totalBits)"
        }
        val seed = params.randomSeed ?: Random.Default.nextLong()
        val state = SolverState(problem, Random(seed))
        // No bestSample yet — first restart is always full random.
        restartPolicy.restart(state, bestSoFar = null)

        var bestObj = Double.POSITIVE_INFINITY
        var bestSample: Sample? = null
        var flipsSinceRestart = 0
        var totalFlips = 0L
        val maxFlips = params.maxFlips

        while (totalFlips < maxFlips) {
            if (state.hardCost == 0) {
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
                continue
            }
            if (restartPolicy.shouldRestart(flipsSinceRestart)) {
                restartPolicy.restart(state, bestSample)
                flipsSinceRestart = 0
                continue
            }
            val move = strategy.pickMove(state)
            if (move == null) {
                restartPolicy.restart(state, bestSample)
                flipsSinceRestart = 0
                continue
            }
            state.apply(move)
            flipsSinceRestart++
            totalFlips++
        }
        return bestSample
    }

    /**
     * Greedy hill-climbing on the objective among feasibility-preserving single-variable
     * moves. Considers a flip on each bool var and a ±1 step on each int var (clamped to
     * the int's domain). Picks the candidate that strictly lowers the objective the most
     * while keeping `hardCost == 0`. Returns `true` if it advanced.
     *
     * Bool flips are evaluated by applying-then-reverting on the live state so the
     * incremental hardCost path runs naturally; int sets do the same with the saved old
     * value.
     */
    private fun greedyObjectiveStep(state: SolverState, objective: Objective): Boolean {
        val baselineSnap = state.assignment.snapshot()
        val baselineObj = objective.evaluate(baselineSnap)
        var bestDelta = 0.0
        var bestMove: Move? = null

        for (b in 0 until problem.numBoolVars) {
            state.apply(Move.BoolFlip(b))
            if (state.hardCost == 0) {
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
            val cur = state.assignment.intValue(i)
            val d = problem.intDomains[i]
            for (target in intArrayOf(cur - 1, cur + 1)) {
                if (target !in d.min..d.max) continue
                state.apply(Move.IntSet(i, target))
                if (state.hardCost == 0) {
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
