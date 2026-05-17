package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Adaptive variant of [WalkSat] (Hoos 2002). The noise probability starts at [baselineNoise]
 * and is steered by a [NoiseController]: it climbs during stalls and decays on improvement,
 * staying in `[baselineNoise, 1.0]`. Literature reports +10-30% on hard random instances over
 * a well-tuned fixed-noise WalkSat.
 *
 * Everything else (tabu, factor-uniform pick, break-score minimisation, aspiration) matches
 * [WalkSat] verbatim.
 */
class AdaptiveWalkSat(
    val baselineNoise: Double = 0.2,
    val tabuTenure: Int = 10,
    theta: Int = 50,
    phi: Double = 0.2,
) : Strategy {

    private val controller = NoiseController(
        initial = baselineNoise,
        theta = theta,
        phi = phi,
        minLevel = baselineNoise,
        maxLevel = 1.0,
    )

    /** Current noise level. Exposed for tests / observability; not part of the Strategy API. */
    val currentNoise: Double get() = controller.level

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        controller.observe(state.cost)
        val noise = controller.level

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

        if (state.rng.nextDouble() < noise) {
            return moves[state.rng.nextInt(moves.size)]
        }

        var bestBreak = Int.MAX_VALUE
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = state.breakScore(m)
            if (brk < bestBreak) {
                bestBreak = brk
                bestCount = 1
                pick = m
            } else if (brk == bestBreak) {
                bestCount++
                if (state.rng.nextInt(bestCount) == 0) pick = m
            }
        }
        return pick
    }
}
