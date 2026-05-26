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
    /** Probability per restart of generating the anchor via crossover of two random
     *  population members instead of single-anchor perturbation. Ignored when
     *  `populationSize < 2`. 0.0 disables crossover (default). 0.2-0.4 is a reasonable
     *  range when running with a populated incumbent set. */
    val crossoverRate: Double = 0.0,
    /** How crossover combines two parents' values per variable. [CrossoverBias.Uniform]
     *  (default) flips a fair coin; [CrossoverBias.BetterBiased] weights toward the
     *  parent with the lower objective. Only consulted when [crossoverRate] > 0 and a
     *  crossover restart fires. */
    val crossoverBias: CrossoverBias = CrossoverBias.Uniform,
    /** Perturbation kind for the kick out of a local optimum. [PerturbationKind.Uniform]
     *  scatters single-variable mutations across the problem; [PerturbationKind.BasinHopping]
     *  randomises every variable of a small set of randomly-picked factors, producing a
     *  coordinated jump biased toward escaping the current basin. */
    val perturbationKind: PerturbationKind = PerturbationKind.Uniform,
    /** When true, crossover transfers entire factor scopes at once instead of mixing
     *  per-variable, preserving co-adapted assignments that uniform recombination would
     *  shred. Off by default (uniform crossover) to keep behaviour stable for callers
     *  that don't opt in. */
    val linkageAware: Boolean = false,
) : RestartPolicy {


    init {
        require(populationSize >= 1) { "populationSize must be ≥ 1, got $populationSize" }
        require(crossoverRate in 0.0..1.0) { "crossoverRate must be in [0, 1], got $crossoverRate" }
    }

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
        // Crossover restart: uniform-recombine two random population members. Skips
        // perturbation since crossover itself diversifies. Only fires with ≥2 incumbents.
        if (population.size >= 2 && state.rng.nextDouble() < crossoverRate) {
            val (parentA, parentB) = pickTwoDistinct(state.rng)
            val probA = crossoverBias.probParentA(parentA.objective, parentB.objective)
            val child = biasedCrossover(state, parentA.sample, parentB.sample, probA, state.rng)
            applyChild(state, child)
            return
        }
        val anchor = pickAnchor(state) ?: bestSoFar
        if (anchor == null) state.restart()
        else anchorAndPerturb(state, anchor, perturbationStrength, perturbationKind)
    }

    private fun pickAnchor(state: LocalSearchState): Sample? {
        if (population.isEmpty()) return null
        return population[state.rng.nextInt(population.size)].sample
    }

    private fun pickTwoDistinct(rng: kotlin.random.Random): Pair<Incumbent, Incumbent> {
        val i = rng.nextInt(population.size)
        var j = rng.nextInt(population.size - 1)
        if (j >= i) j++
        return population[i] to population[j]
    }

    private fun biasedCrossover(
        state: LocalSearchState, a: Sample, b: Sample, probA: Double, rng: kotlin.random.Random,
    ): Sample {
        if (linkageAware) return linkageAwareCrossover(state, a, b, probA, rng)
        val bools = BooleanArray(a.bools.size) { if (rng.nextDouble() < probA) a.bools[it] else b.bools[it] }
        val ints = IntArray(a.ints.size) { if (rng.nextDouble() < probA) a.ints[it] else b.ints[it] }
        return Sample(bools, ints)
    }

    /** Linkage-aware crossover: copy entire factor scopes from one parent at a time.
     *  Each factor's bool+int scope is the linkage group; we pick a parent for the group
     *  and copy all its variables together, preserving co-adapted assignments that uniform
     *  crossover would shred. Variables touched by multiple factors get assigned by the
     *  *last* factor processed (later factors override). Vars touched by no factor fall
     *  back to a per-variable coin flip. */
    private fun linkageAwareCrossover(
        state: LocalSearchState, a: Sample, b: Sample, probA: Double, rng: kotlin.random.Random,
    ): Sample {
        val bools = BooleanArray(a.bools.size)
        val ints = IntArray(a.ints.size)
        val boolSet = BooleanArray(a.bools.size)
        val intSet = BooleanArray(a.ints.size)
        for (f in state.factors) {
            val pickA = rng.nextDouble() < probA
            val source = if (pickA) a else b
            for (v in f.boolVars) {
                if (v in source.bools.indices) { bools[v] = source.bools[v]; boolSet[v] = true }
            }
            for (v in f.intVars) {
                if (v in source.ints.indices) { ints[v] = source.ints[v]; intSet[v] = true }
            }
        }
        for (i in bools.indices) if (!boolSet[i]) {
            bools[i] = if (rng.nextDouble() < probA) a.bools[i] else b.bools[i]
        }
        for (i in ints.indices) if (!intSet[i]) {
            ints[i] = if (rng.nextDouble() < probA) a.ints[i] else b.ints[i]
        }
        return Sample(bools, ints)
    }

    private fun applyChild(state: LocalSearchState, child: Sample) {
        val problem = state.problem
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, child.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, child.ints[i])
        state.recompute()
    }

    /** Insertion-sort by ascending objective. Population is small (typical 1-5) so the
     *  O(N) cost is negligible compared to the LS work this restart triggers. */
    private fun insertSortedByObjective(item: Incumbent) {
        var idx = 0
        while (idx < population.size && population[idx].objective <= item.objective) idx++
        population.add(idx, item)
    }
}
