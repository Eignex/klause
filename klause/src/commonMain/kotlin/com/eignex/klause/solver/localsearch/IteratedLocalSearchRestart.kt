package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Sample

/**
 * Iterated Local Search restart policy. Maintains a *population* of incumbent local
 * optima and restarts by picking one at random to anchor, then applying a random
 * perturbation. Compared to [AdaptivePerturbationRestart], which always anchors to the
 * global best-feasible-so-far, ILS allows the search to drift through sub-optimal
 * regions: an [AcceptanceCriterion] decides whether a new local optimum joins the
 * population (Lourenço–Martin–Stützle 2003).
 *
 *  - [populationSize] — number of incumbents tracked. Default 1 = classic single-anchor
 *    ILS. Larger values give a population-based variant: each restart picks a random
 *    member, and when a new optimum is accepted it replaces the population's worst.
 *    Worth bumping to 3-5 on problems with multiple promising basins.
 *
 *  - [acceptance] — when [onLocalOptimum] fires with a new local optimum, this decides
 *    whether the new sample joins the population (replacing the worst member if at
 *    capacity). Comparison is against the population's *worst* incumbent so a candidate
 *    that beats only the weakest member is admitted; under single-incumbent mode this
 *    reduces to comparing against the lone incumbent.
 *
 *  - [adaptivePerturbation] — when true, ramps [perturbationStrength] up on stalls (no
 *    improvement over the population for [adaptiveStallThreshold] consecutive local
 *    optima) and decays it on improvement. Bounded by [maxPerturbationStrength].
 *
 *  - Falls back to a full random restart when the population is empty — same behaviour
 *    as [AdaptivePerturbationRestart] before the first feasible.
 */
class IteratedLocalSearchRestart(
    val maxFlipsBeforeRestart: Int = 1_000,
    val initialPerturbationStrength: Int = 5,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    val adaptivePerturbation: Boolean = true,
    val adaptiveStallThreshold: Int = 3,
    val maxPerturbationStrength: Int = 50,
    val populationSize: Int = 1,
) : RestartPolicy {

    init { require(populationSize >= 1) { "populationSize must be ≥ 1, got $populationSize" } }

    /** Population of (sample, objective) entries, sorted-ish: index 0 is the best by
     *  objective, last is the worst. Kept compact (no nulls) up to [populationSize]. */
    private val population: MutableList<Incumbent> = ArrayList(populationSize)
    var perturbationStrength: Int = initialPerturbationStrength
        private set
    private var stallCount: Int = 0

    /** Read-only view for tests / diagnostics. */
    val incumbents: List<Incumbent> get() = population

    data class Incumbent(val sample: Sample, val objective: Double)

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
        val worstObjective = if (population.size < populationSize) Double.POSITIVE_INFINITY
                             else population.last().objective
        val accept = population.isEmpty() || acceptance.accept(objective, worstObjective, state.rng)
        if (accept) {
            insertSortedByObjective(Incumbent(sample, objective))
            // Evict the worst (last) when over capacity.
            while (population.size > populationSize) population.removeAt(population.size - 1)
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
        val anchor = pickAnchor(state) ?: bestSoFar
        if (anchor == null) state.restart() else anchorAndPerturb(state, anchor, perturbationStrength)
    }

    private fun pickAnchor(state: LocalSearchState): Sample? {
        if (population.isEmpty()) return null
        return population[state.rng.nextInt(population.size)].sample
    }

    /** Insertion-sort by ascending objective. Population is small (typical 1-5) so the
     *  O(N) cost is negligible compared to the LS work this restart triggers. */
    private fun insertSortedByObjective(item: Incumbent) {
        var idx = 0
        while (idx < population.size && population[idx].objective <= item.objective) idx++
        population.add(idx, item)
    }
}
