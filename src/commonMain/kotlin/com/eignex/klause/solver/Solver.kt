package com.eignex.klause.solver

import com.eignex.klause.solver.strategy.Strategy
import com.eignex.klause.solver.strategy.WalkSat
import kotlin.random.Random

/**
 * Local-search solver around a [Problem]. The solver itself only carries configuration
 * (problem, strategy, restart cadence). All per-draw state — RNG, assignment, factor
 * payloads, the dedup window — lives inside the [sample] sequence so two concurrent draws
 * never share state.
 */
class Solver(
    val problem: Problem,
    val strategy: Strategy = WalkSat(),
    val maxFlipsBeforeRestart: Int = 10_000,
) {

    /**
     * Lazy sequence of hard-feasible assignments. After each yield the search restarts from
     * a freshly randomized state.
     *
     * Diversity is enforced against a rolling window of the [recentWindow] most recently
     * yielded samples: a fresh hard-feasible assignment is yielded only when it differs from
     * every sample in that window by at least [minHammingDistance] primitive variables
     * (Boolean bit flips and integer-value differences each count as one). The defaults
     * (`minHammingDistance=1`, `recentWindow=16`) give a sliding-window deduplication that
     * keeps memory and per-yield work bounded; raise [minHammingDistance] for UUID-style
     * diverse sampling, raise [recentWindow] toward [Int.MAX_VALUE] for global uniqueness,
     * or set [minHammingDistance] to 0 to allow duplicates.
     *
     * If [randomSeed] is null a fresh seed is drawn from [Random.Default] so independent
     * `sample()` calls from the same [Solver] instance produce independent draws. Pass an
     * explicit seed to make a draw reproducible.
     *
     * If the local solution space within the window is exhausted before the iteration
     * budget the sequence ends.
     */
    fun sample(
        maxFlips: Long = Long.MAX_VALUE,
        randomSeed: Long? = null,
        minHammingDistance: Int = 1,
        recentWindow: Int = 16,
    ): Sequence<Sample> {
        require(recentWindow >= 0) { "recentWindow must be non-negative, got $recentWindow" }
        val totalBits = problem.numBoolVars + problem.numIntVars
        require(minHammingDistance <= totalBits) {
            "minHammingDistance ($minHammingDistance) exceeds the total variable count ($totalBits); " +
                "no two assignments can ever satisfy that distance."
        }
        val seed = randomSeed ?: Random.Default.nextLong()
        return sequence {
            val state = SolverState(problem, Random(seed))
            val window = ArrayDeque<Sample>()
            state.restart()
            var flipsSinceRestart = 0
            var totalFlips = 0L

            while (totalFlips < maxFlips) {
                if (state.hardCost == 0) {
                    val snap = state.assignment.snapshot()
                    if (farEnough(snap, window, minHammingDistance, recentWindow)) {
                        yield(snap)
                        if (recentWindow > 0) {
                            if (window.size >= recentWindow) window.removeFirst()
                            window.addLast(snap)
                        }
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
                totalFlips++
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
