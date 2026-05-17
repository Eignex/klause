package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Adaptive variant of [WalkSat] (Hoos 2002). The noise probability starts at [baselineNoise]
 * and is steered by a [NoiseController]: it climbs during stalls and decays on improvement,
 * staying in `[baselineNoise, 1.0]`. Literature reports +10-30% on hard random instances over
 * a well-tuned fixed-noise WalkSat.
 *
 * Subclasses [WalkSat] and overrides only the noise hook — all other behaviour (tabu,
 * factor-uniform pick, break-score minimisation, aspiration) is inherited unchanged.
 */
class AdaptiveWalkSat(
    val baselineNoise: Double = 0.2,
    tabu: TabuFilter = TabuFilter(tenure = 10),
    theta: Int = 50,
    phi: Double = 0.2,
) : WalkSat(noise = baselineNoise, tabu = tabu) {

    private val controller = NoiseController(
        initial = baselineNoise,
        theta = theta,
        phi = phi,
        minLevel = baselineNoise,
        maxLevel = 1.0,
    )

    /** Current noise level. Exposed for tests / observability; not part of the Strategy API. */
    val currentNoise: Double get() = controller.level

    override fun currentNoise(state: LocalSearchState): Double {
        controller.observe(state.cost)
        return controller.level
    }
}
