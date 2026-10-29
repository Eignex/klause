package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.koblas.DenseVector
import com.eignex.kumulant.bandit.ContextualBandit
import com.eignex.kumulant.bandit.contextual.LinearRegressionSpec
import com.eignex.kumulant.bandit.contextual.RegressionContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualSpec
import com.eignex.kumulant.bandit.materialize
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stat.regression.glm.MultivariateGaussian
import kotlin.math.abs
import kotlin.random.Random

/**
 * Iterated Local Search restart policy. Maintains a *population* of feasible basin
 * anchors and restarts by picking one at random to anchor, then applying a random
 * perturbation. Compared to [AdaptivePerturbationRestart], which always anchors to the
 * global best-feasible-so-far, ILS allows the search to drift through sub-optimal
 * regions: an [AcceptanceCriterion] decides whether a feasible anchor joins the
 * population. See Lourenço, Martin and Stützle, "Iterated Local Search", Handbook of
 * Metaheuristics (2003), https://doi.org/10.1007/0-306-48056-5_11.
 *
 *  - [populationSize] — number of incumbents tracked. Default 1 = classic single-anchor
 *    ILS. Larger values give a population-based variant: each restart picks a random
 *    member, and when an anchor is accepted it replaces the population's worst.
 *
 *  - [acceptance] — when [onLocalOptimum] or [onFeasibleStall] offers a feasible candidate, this decides
 *    whether the new sample joins the population (replacing the worst member if at
 *    capacity). Comparison is against the population's *worst* incumbent so a candidate
 *    that beats only the weakest member is admitted; under single-incumbent mode this
 *    reduces to comparing against the lone incumbent.
 *
 *  - [adaptivePerturbation] — when true, ramps [perturbationStrength] up on stalls
 *    ([adaptiveStallThreshold] consecutive rejected anchors) and decays it when an
 *    anchor is accepted. Bounded by [maxPerturbationStrength].
 *
 *  - Falls back to a full random restart when the population is empty — same behaviour
 *    as [AdaptivePerturbationRestart] before the first feasible.
 */
class IteratedLocalSearchRestart(
    /** Steps without improvement before a restart. */
    val maxFlipsBeforeRestart: Int = 1_000,
    /** Initial perturbation strength. */
    val initialPerturbationStrength: Int = 5,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    val adaptivePerturbation: Boolean = true,
    val adaptiveStallThreshold: Int = 3,
    val maxPerturbationStrength: Int = 50,
    val populationSize: Int = 1,
    /** Probability per restart of generating the anchor via crossover of two random
     *  population members instead of single-anchor perturbation. Ignored when
     *  `populationSize < 2`; 0.0 disables crossover. */
    val crossoverRate: Double = 0.0,
    /** How crossover combines two parents' values per variable. [CrossoverBias.Uniform]
     *  (default) flips a fair coin; [CrossoverBias.BetterBiased] weights toward the
     *  parent with the lower objective. Only consulted when [crossoverRate] > 0 and a
     *  crossover restart fires. */
    val crossoverBias: CrossoverBias = CrossoverBias.Uniform,
    /** Perturbation kind for the kick from a feasible anchor. [PerturbationKind.Uniform]
     *  scatters single-variable mutations across the problem; [PerturbationKind.BasinHopping]
     *  randomises every variable of a small set of randomly-picked factors, producing a
     *  coordinated jump biased toward escaping the current basin. */
    val perturbationKind: PerturbationKind = PerturbationKind.Uniform,
    /** When true, crossover transfers entire factor scopes at once instead of mixing
     *  per-variable, preserving co-adapted assignments that uniform recombination would
     *  shred. */
    val linkageAware: Boolean = false,
    /** Optional contextual-bandit acceptance: when non-null, the accept/reject of a feasible
     *  anchor is decided by any 2-arm kumulant [ContextualBandit] (arm 0 = accept, 1 = reject) over
     *  context features (margin vs the population's worst and best, stall fraction) instead of the
     *  fixed [acceptance] criterion. The bandit is rewarded by whether the population best improved
     *  by the next candidate anchor, so it learns *when* drifting through worse basins pays off. The
     *  [acceptanceBandit] factory builds the default; null (default) keeps the fixed criterion. */
    private val acceptanceBandit: ContextualBandit? = null,
) : RestartPolicy {

    init {
        require(populationSize >= 1) { "populationSize must be ≥ 1, got $populationSize" }
        require(crossoverRate in 0.0..1.0) { "crossoverRate must be in [0, 1], got $crossoverRate" }
    }

    /** Incumbents sorted by ascending objective (index 0 best, last worst), at most [populationSize]. */
    private val population: MutableList<Incumbent> = ArrayList(populationSize)
    var perturbationStrength: Int = initialPerturbationStrength
        private set
    private var stallCount: Int = 0

    /** Last completed engine-prepared restart's input-change result for unconditional AllDifferent
     *  boards. `null` denotes no completed observed restart, including unanchored and non-board runs.
     *  The outcome is pending while a sliced greedy-repair pass is suspended. */
    var lastBoardRestartOutcome: BoardRestartOutcome? = null
        private set
    internal var changedBoardRestarts = 0L
        private set
    internal var declinedBoardRestarts = 0L
        private set

    // Pending bandit decision, rewarded at the next candidate anchor by whether the population best
    // improved since (delayed credit, the standard meta-bandit signal). -1 = nothing pending.
    private var pendingArm: Int = -1
    private var pendingContext: DoubleArray? = null
    private var pendingBestBefore: Double = Double.POSITIVE_INFINITY

    /** Read-only view of the population. */
    val incumbents: List<Incumbent> get() = population

    /** A feasible population anchor and its objective, without an optimality claim. */
    data class Incumbent(
        /** The incumbent assignment. */
        val sample: Sample,
        /** Its objective value. */
        val objective: Double,
    )

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= maxFlipsBeforeRestart

    /** Drop the tracked incumbents and reset perturbation/adaptation state. A reused policy instance
     *  must not carry incumbents — or their variable arity — across independent solves, else the
     *  anchor/crossover paths index a stale sample against a different problem's dimensions. The
     *  learned [acceptanceBandit] weights are intentionally kept; only the pending, half-credited
     *  decision is cleared. */
    override fun reset() {
        population.clear()
        stallCount = 0
        lastBoardRestartOutcome = null
        changedBoardRestarts = 0L
        declinedBoardRestarts = 0L
        perturbationStrength = initialPerturbationStrength
        pendingArm = -1
        pendingContext = null
        pendingBestBefore = Double.POSITIVE_INFINITY
    }

    override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) =
        retainAnchor(state, sample, objective)

    override fun onFeasibleStall(state: LocalSearchState, sample: Sample, objective: Double) =
        retainAnchor(state, sample, objective)

    private fun retainAnchor(state: LocalSearchState, sample: Sample, objective: Double) {
        val worstObjective = if (population.size < populationSize) {
            Double.POSITIVE_INFINITY
        } else {
            population.last().objective
        }
        val accept = if (acceptanceBandit != null) {
            banditAccept(acceptanceBandit, objective, worstObjective)
        } else {
            population.isEmpty() || acceptance.accept(objective, worstObjective, state.rng)
        }
        if (accept) {
            insertSortedByObjective(Incumbent(sample, objective))
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

    /** Decide accept/reject via the contextual bandit, first crediting the previous decision by
     *  whether the population best improved since it was made. The first candidate (empty
     *  population) is always accepted to seed the population. */
    private fun banditAccept(bandit: ContextualBandit, objective: Double, worstObjective: Double): Boolean {
        val bestBefore = population.firstOrNull()?.objective ?: Double.POSITIVE_INFINITY
        pendingContext?.let { ctx ->
            val reward = if (bestBefore < pendingBestBefore) 1.0 else 0.0
            bandit.update(pendingArm, DenseVector.of(ctx), reward, 1.0)
        }
        if (population.isEmpty()) {
            pendingContext = null
            return true
        }
        val ctx = acceptanceFeatures(objective, worstObjective, bestBefore)
        val arm = bandit.choose(DenseVector.of(ctx))
        pendingArm = arm
        pendingContext = ctx
        pendingBestBefore = bestBefore
        return arm == ACCEPT_ARM
    }

    /** Context for the acceptance bandit: how the candidate compares to the population's worst and
     *  best incumbents, and how deep the current stall is — all normalised to roughly [-1, 1]. */
    private fun acceptanceFeatures(objective: Double, worstObjective: Double, bestObjective: Double): DoubleArray {
        val vsWorst = if (worstObjective.isFinite()) (worstObjective - objective) / (abs(worstObjective) + 1.0) else 1.0
        val vsBest = if (bestObjective.isFinite()) (bestObjective - objective) / (abs(bestObjective) + 1.0) else 0.0
        val stallFrac = (stallCount.toDouble() / adaptiveStallThreshold).coerceIn(0.0, 1.0)
        return doubleArrayOf(vsWorst.coerceIn(-1.0, 1.0), vsBest.coerceIn(-1.0, 1.0), stallFrac)
    }

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        lastBoardRestartOutcome = null
        // Crossover restart: recombine two random population members; skips perturbation since
        // crossover itself diversifies. Only fires with ≥2 incumbents.
        if (population.size >= 2 && state.rng.nextDouble() < crossoverRate) {
            val (parentA, parentB) = pickTwoDistinct(state.rng)
            val probA = crossoverBias.probParentA(parentA.objective, parentB.objective)
            val child = biasedCrossover(state, parentA.sample, parentB.sample, probA, state.rng)
            applyChild(state, child)
            return
        }
        val anchor = pickAnchor(state) ?: bestSoFar
        if (anchor == null) {
            state.restart()
        } else {
            anchorAndPerturb(state, anchor, perturbationStrength, perturbationKind)
        }
    }

    internal fun recordBoardRestart(outcome: BoardRestartOutcome) {
        lastBoardRestartOutcome = outcome
        if (outcome == BoardRestartOutcome.ChangedInputs) changedBoardRestarts++ else declinedBoardRestarts++
    }

    private fun pickAnchor(state: LocalSearchState): Sample? {
        if (population.isEmpty()) return null
        return population[state.rng.nextInt(population.size)].sample
    }

    private fun pickTwoDistinct(rng: Random): Pair<Incumbent, Incumbent> {
        val i = rng.nextInt(population.size)
        var j = rng.nextInt(population.size - 1)
        if (j >= i) j++
        return population[i] to population[j]
    }

    private fun biasedCrossover(state: LocalSearchState, a: Sample, b: Sample, probA: Double, rng: Random): Sample {
        if (linkageAware) return linkageAwareCrossover(state, a, b, probA, rng)
        val bools = BooleanArray(state.problem.numBoolVars) { pickBool(a, b, it, probA, rng) }
        val ints = LongArray(state.problem.numIntVars) { pickLong(a, b, it, probA, rng) }
        return Sample(bools, ints)
    }

    /** Pick parent A's value at index [i] with probability [probA], falling back to the
     *  other parent when the chosen one is too short. Incumbents captured at different
     *  arities within one solve can differ in array length, so neither parent is a safe
     *  bound for the child (sized from the problem). */
    private fun pickBool(a: Sample, b: Sample, i: Int, probA: Double, rng: Random): Boolean {
        val preferA = rng.nextDouble() < probA
        return when {
            preferA && i < a.numBoolVars -> a.boolValue(i)
            !preferA && i < b.numBoolVars -> b.boolValue(i)
            i < a.numBoolVars -> a.boolValue(i)
            i < b.numBoolVars -> b.boolValue(i)
            else -> false
        }
    }

    private fun pickLong(a: Sample, b: Sample, i: Int, probA: Double, rng: Random): Long {
        val preferA = rng.nextDouble() < probA
        return when {
            preferA && i < a.numIntVars -> a.intValue(i)
            !preferA && i < b.numIntVars -> b.intValue(i)
            i < a.numIntVars -> a.intValue(i)
            i < b.numIntVars -> b.intValue(i)
            else -> 0L
        }
    }

    /** Linkage-aware crossover: copy entire factor scopes from one parent at a time.
     *  Each factor's bool+int scope is the linkage group, copied together from one parent.
     *  A variable in several scopes takes the last factor's pick; a variable in no scope
     *  falls back to a per-variable coin flip. */
    private fun linkageAwareCrossover(
        state: LocalSearchState,
        a: Sample,
        b: Sample,
        probA: Double,
        rng: Random,
    ): Sample {
        val bools = BooleanArray(state.problem.numBoolVars)
        val ints = LongArray(state.problem.numIntVars)
        val boolSet = BooleanArray(bools.size)
        val intSet = BooleanArray(ints.size)
        for ((fid, _) in state.factors.withIndex()) {
            val pickA = rng.nextDouble() < probA
            val source = if (pickA) a else b
            val scope = state.problem.factors[fid]
            for (v in scope.boolVars) {
                if (v in (0 until source.numBoolVars)) {
                    bools[v] = source.boolValue(v)
                    boolSet[v] = true
                }
            }
            for (v in scope.intVars) {
                if (v in (0 until source.numIntVars)) {
                    ints[v] = source.intValue(v)
                    intSet[v] = true
                }
            }
        }
        for (i in bools.indices) {
            if (!boolSet[i]) {
                bools[i] = pickBool(a, b, i, probA, rng)
            }
        }
        for (i in ints.indices) {
            if (!intSet[i]) {
                ints[i] = pickLong(a, b, i, probA, rng)
            }
        }
        return Sample(bools, ints)
    }

    private fun applyChild(state: LocalSearchState, child: Sample) {
        val problem = state.problem
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, child.boolValue(b))
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, child.intValue(i))
        state.resetStepCounters()
        state.recompute()
    }

    private fun insertSortedByObjective(item: Incumbent) {
        var idx = 0
        while (idx < population.size && population[idx].objective <= item.objective) idx++
        population.add(idx, item)
    }

    /** Factory for the [acceptanceBandit] constructor argument. */
    companion object {
        private const val ACCEPT_ARM = 0
        private const val ACCEPT_FEATURES = 3

        /** A 2-arm (accept / reject) contextual bandit over the acceptance features: Bayesian linear
         *  regression + [MultivariateGaussian] Thompson posterior. Pass to
         *  [IteratedLocalSearchRestart]'s `acceptanceBandit`. */
        fun acceptanceBandit(
            seed: Long = 0L,
            exploration: Double = 1.0,
            priorVariance: Double = 1.0,
        ): RegressionContextualBandit<*> {
            val regression = LinearRegressionSpec.Bayesian(ACCEPT_FEATURES, priorVariance)
            val spec = RegressionContextualSpec(2, regression, MultivariateGaussian, exploration, regression)
            return spec.materialize(Random(seed), Concurrency.None)
        }
    }
}
