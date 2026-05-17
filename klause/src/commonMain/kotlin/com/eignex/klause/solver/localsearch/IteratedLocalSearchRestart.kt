package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Sample

/**
 * Iterated Local Search restart policy. Maintains an *incumbent* — the most recently
 * accepted local optimum — and restarts by anchoring to it and applying a random
 * perturbation. Compared to [AdaptivePerturbationRestart], which always anchors to the
 * global best-feasible-so-far, ILS allows the search to drift through sub-optimal
 * regions: an [AcceptanceCriterion] decides whether a new local optimum becomes the
 * incumbent (Lourenço–Martin–Stützle 2003).
 *
 *  - [acceptance] — when [onLocalOptimum] fires with a new local optimum, this decides
 *    whether the new sample replaces the incumbent. Default [AcceptanceCriterion.Improving]
 *    only accepts strictly better optima, giving the simplest ILS variant (essentially
 *    multi-start with a perturbation-anchored restart).
 *
 *  - [adaptivePerturbation] — when true, ramps [perturbationStrength] up on stalls (no
 *    improvement over the incumbent for [adaptiveStallThreshold] consecutive local
 *    optima) and decays it on improvement. Bounded by [maxPerturbationStrength].
 *
 *  - Falls back to a full random restart when no local optimum has been observed yet —
 *    same behaviour as [AdaptivePerturbationRestart] before the first feasible.
 */
class IteratedLocalSearchRestart(
    val maxFlipsBeforeRestart: Int = 1_000,
    val initialPerturbationStrength: Int = 5,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    val adaptivePerturbation: Boolean = true,
    val adaptiveStallThreshold: Int = 3,
    val maxPerturbationStrength: Int = 50,
) : RestartPolicy {

    private var incumbent: Sample? = null
    private var incumbentObjective: Double = Double.POSITIVE_INFINITY
    var perturbationStrength: Int = initialPerturbationStrength
        private set
    private var stallCount: Int = 0

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
        val current = incumbent
        val accept = current == null || acceptance.accept(objective, incumbentObjective)
        if (accept) {
            incumbent = sample
            incumbentObjective = objective
            stallCount = 0
            if (adaptivePerturbation && perturbationStrength > initialPerturbationStrength) {
                perturbationStrength = (perturbationStrength - 1).coerceAtLeast(1)
            }
        } else {
            stallCount++
            if (adaptivePerturbation && stallCount >= adaptiveStallThreshold) {
                perturbationStrength = (perturbationStrength + 1).coerceAtMost(maxPerturbationStrength)
                stallCount = 0
            }
        }
    }

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        val anchor = incumbent ?: bestSoFar
        if (anchor == null) state.restart() else anchorAndPerturb(state, anchor, perturbationStrength)
    }
}

