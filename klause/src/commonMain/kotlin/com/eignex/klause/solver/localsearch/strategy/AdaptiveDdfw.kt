package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Adaptive variant of [Ddfw]. The weight increment grows during stalls (faster
 * "hard-clause" learning) and shrinks back on improvement. The [NoiseController]'s
 * `level` maps to a multiplier on the baseline increment:
 *   `increment = baselineIncrement * (1 + level * 4)`
 * so at level 0 we match the baseline and at level 1 the increment is 5× the baseline.
 *
 * Subclasses [Ddfw] and overrides only the increment hook; weight transfer, noise
 * probability, and tabu filtering come from the parent unchanged.
 */
class AdaptiveDdfw(
    noiseProbability: Double = 0.05,
    initWeight: Double = 1.0,
    val baselineIncrement: Double = 1.0,
    tabu: TabuFilter = TabuFilter.Disabled,
    theta: Int = 50,
    phi: Double = 0.2,
    /** Opt-in EWMA-mode for the internal [NoiseController]; see [AdaptiveWalkSat]'s
     *  matching parameter. */
    ewmaAlpha: Double? = null,
) : Ddfw(
    noiseProbability = noiseProbability,
    initWeight = initWeight,
    increment = baselineIncrement,
    tabu = tabu,
) {

    private val controller = NoiseController(
        initial = 0.0,
        theta = theta,
        phi = phi,
        ewmaAlpha = ewmaAlpha,
    )

    val currentIncrement: Double get() = baselineIncrement * (1.0 + controller.level * 4.0)

    override fun currentIncrement(state: LocalSearchState): Double {
        controller.observe(state.cost)
        return currentIncrement
    }
}
