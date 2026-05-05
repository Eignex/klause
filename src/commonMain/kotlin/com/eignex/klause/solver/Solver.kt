package com.eignex.klause.solver

import com.eignex.klause.solver.strategy.Strategy
import com.eignex.klause.solver.strategy.WalkSat
import kotlin.random.Random

/**
 * Local-search solver around a [Problem]. [sample] is a lazy sequence of hard-feasible
 * assignments; the search restarts after each yield so callers can `take(n)` to draw `n`
 * samples (with no formal uniformity guarantee, see README).
 */
class Solver(
    val problem: Problem,
    val randomSeed: Long = 0L,
    val strategy: Strategy = WalkSat(),
    val maxFlipsBeforeRestart: Int = 10_000,
) {

    fun sample(maxFlips: Long = Long.MAX_VALUE): Sequence<Sample> = sequence {
        val state = SolverState(problem, Random(randomSeed))
        state.restart()
        var flipsSinceRestart = 0
        var totalFlips = 0L

        while (totalFlips < maxFlips) {
            if (state.hardCost == 0) {
                yield(state.assignment.snapshot())
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
