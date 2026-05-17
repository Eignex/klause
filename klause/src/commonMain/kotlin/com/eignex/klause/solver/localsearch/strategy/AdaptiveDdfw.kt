package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Adaptive variant of [Ddfw]. The weight increment grows during stalls (faster
 * "hard-clause" learning) and shrinks back on improvement. The [NoiseController]'s
 * `level` maps to a multiplier on the baseline increment:
 *   `increment = baselineIncrement * (1 + level * 4)`
 * so at level 0 we match the baseline and at level 1 the increment is 5× the baseline.
 */
class AdaptiveDdfw(
    val noiseProbability: Double = 0.05,
    val initWeight: Double = 1.0,
    val baselineIncrement: Double = 1.0,
    val tabuTenure: Int = 0,
    theta: Int = 50,
    phi: Double = 0.2,
) : Strategy {

    private val controller = NoiseController(initial = 0.0, theta = theta, phi = phi)
    private var lastUpdateStep: Long = -1L

    val currentIncrement: Double get() = baselineIncrement * (1.0 + controller.level * 4.0)

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        controller.observe(state.cost)
        if (state.step > 0 && state.step != lastUpdateStep) {
            updateWeights(state, currentIncrement)
            lastUpdateStep = state.step
        }
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = if (tabuTenure > 0) {
            val nonTaboo = raw.filter { !state.isTaboo(it, tabuTenure) }
            if (nonTaboo.isEmpty()) raw else nonTaboo
        } else raw

        if (state.rng.nextDouble() < noiseProbability) {
            return moves[state.rng.nextInt(moves.size)]
        }
        var best = moves[0]
        var bestScore = weightedBreakScore(state, best)
        for (i in 1 until moves.size) {
            val s = weightedBreakScore(state, moves[i])
            if (s < bestScore) {
                best = moves[i]
                bestScore = s
            }
        }
        return best
    }

    private fun weightedBreakScore(state: LocalSearchState, move: Move): Double {
        val w = state.factorWeights
        return when (move) {
            is Move.BoolFlip -> {
                var sum = 0.0
                for (fid in state.problem.boolOccurrences[move.varId]) {
                    val f = state.factors[fid]
                    if (f.deltaIfBoolFlipped(state, fid, move.varId) > 0) sum += w[fid]
                }
                sum
            }
            is Move.IntSet -> {
                var sum = 0.0
                for (fid in state.problem.intOccurrences[move.varId]) {
                    val f = state.factors[fid]
                    if (f.deltaIfIntSet(state, fid, move.varId, move.newValue) > 0) sum += w[fid]
                }
                sum
            }
        }
    }

    private fun updateWeights(state: LocalSearchState, increment: Double) {
        val w = state.factorWeights
        val violated = state.violated.toIntArray()
        for (v in violated) {
            val neighbors = state.problem.factorNeighbors[v]
            var bestNeighbor = -1
            var bestWeight = -1.0
            for (n in neighbors) {
                if (state.violated.contains(n)) continue
                if (w[n] > bestWeight) {
                    bestWeight = w[n]
                    bestNeighbor = n
                }
            }
            if (bestNeighbor < 0) {
                w[v] += increment
                continue
            }
            val transfer = if (w[bestNeighbor] >= initWeight + increment) increment else increment / 2.0
            w[v] += transfer
            w[bestNeighbor] -= transfer
        }
    }
}
