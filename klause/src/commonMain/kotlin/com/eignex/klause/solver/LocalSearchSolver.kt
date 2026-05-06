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
    val maxFlipsBeforeRestart: Int = 10_000,
) : Sampler<LocalSearchParams> {

    override fun solve(params: LocalSearchParams): SolveResult =
        sample(params).firstOrNull()?.let(SolveResult::Sat)
            ?: SolveResult.Unknown

    override fun sample(params: LocalSearchParams): Sequence<Sample> =
        streamImpl(params.copy(minHammingDistance = 0, recentWindow = 0))

    override fun enumerate(params: LocalSearchParams): Sequence<Sample> =
        streamImpl(params)

    fun solve(): SolveResult = solve(LocalSearchParams())
    fun sample(): Sequence<Sample> = sample(LocalSearchParams())
    fun enumerate(): Sequence<Sample> = enumerate(LocalSearchParams())

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
            state.restart()
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
                    state.restart()
                    flipsSinceRestart = 0
                    continue
                }
                if (flipsSinceRestart >= maxFlipsBeforeRestart) {
                    state.restart()
                    flipsSinceRestart = 0
                    continue
                }
                val move = strategy.pickMove(state)
                if (move == null) {
                    state.restart()
                    flipsSinceRestart = 0
                    continue
                }
                state.apply(move)
                flipsSinceRestart++
                flipsSinceYield++
            }
        }
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
