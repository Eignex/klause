package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.math.pow

/**
 * Adaptive variant of [ProbSat]. The break-exponent `cb` starts at [baselineCb] and is
 * steered down during stalls (more diversification: distribution flattens toward uniform)
 * and back up on improvement (sharper preference for low-break moves).
 *
 * The [NoiseController]'s `level` maps to an exponent multiplier:
 *   `cb = baselineCb * (1 - level * 0.5)`
 * so at level 0 we run the paper defaults; at level 1 we run cb at half its baseline.
 */
class AdaptiveProbSat(
    val baselineCb: Double = 2.06,
    val eps: Double = 1.0,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
    theta: Int = 50,
    phi: Double = 0.2,
) : Strategy {

    private val controller = NoiseController(initial = 0.0, theta = theta, phi = phi)

    val currentCb: Double get() = baselineCb * (1.0 - controller.level * 0.5)

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        controller.observe(state.cost)
        val cb = currentCb

        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)
        if (moves.size == 1) return moves[0]

        var totalWeight = 0.0
        val weights = DoubleArray(moves.size)
        for (i in moves.indices) {
            val brk = state.breakScore(moves[i])
            val w = (eps + brk).pow(-cb)
            weights[i] = w
            totalWeight += w
        }
        if (totalWeight == 0.0) return moves[state.rng.nextInt(moves.size)]
        var draw = state.rng.nextDouble() * totalWeight
        for (i in moves.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return moves[i]
        }
        return moves[moves.size - 1]
    }
}
