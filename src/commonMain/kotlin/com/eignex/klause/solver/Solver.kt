package com.eignex.klause.solver

import com.eignex.klause.solver.strategy.Strategy
import com.eignex.klause.solver.strategy.WalkSat
import kotlin.random.Random

/**
 * Local-search solver around a [Problem]. [sample] is a lazy sequence of hard-feasible
 * assignments; after each yield the search restarts from a freshly randomized state.
 *
 * Diversity is enforced by [minHammingDistance]: a fresh hard-feasible assignment is yielded
 * only when it differs from every prior yielded assignment by at least this many primitive
 * variables (Boolean bit flips and integer-value differences both count as one). The default
 * (1) gives plain deduplication; raise it for UUID-style diverse sampling, or set to 0 to
 * allow duplicates. If a hard-feasible state is rejected for being too close to a prior
 * sample the search restarts and retries; once the iteration budget is exhausted (or the
 * solution space is exhausted) the sequence ends.
 */
class Solver(
    val problem: Problem,
    val randomSeed: Long = 0L,
    val strategy: Strategy = WalkSat(),
    val maxFlipsBeforeRestart: Int = 10_000,
    val minHammingDistance: Int = 1,
) {

    fun sample(maxFlips: Long = Long.MAX_VALUE): Sequence<Sample> = sequence {
        val state = SolverState(problem, Random(randomSeed))
        val seen = mutableListOf<Sample>()
        state.restart()
        var flipsSinceRestart = 0
        var totalFlips = 0L

        while (totalFlips < maxFlips) {
            if (state.hardCost == 0) {
                val snap = state.assignment.snapshot()
                if (farEnough(snap, seen)) {
                    seen += snap
                    yield(snap)
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

    private fun farEnough(candidate: Sample, prior: List<Sample>): Boolean {
        if (minHammingDistance <= 0) return true
        for (p in prior) {
            if (hammingDistance(candidate, p) < minHammingDistance) return false
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
