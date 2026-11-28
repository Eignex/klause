package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.koblas.DenseVector
import com.eignex.kumulant.bandit.ContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualSpec
import com.eignex.kumulant.bandit.materialize
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.schema.spec.BayesianRegression
import com.eignex.kumulant.stat.regression.glm.MultivariateGaussian
import kotlin.math.abs
import kotlin.random.Random

/**
 * Iterated Local Search restart policy: restarts from a random member of a population of feasible anchors plus a
 * random perturbation, or fully at random while the population is empty. Lourenço, Martin and Stützle, "Iterated
 * Local Search", Handbook of Metaheuristics (2003), https://doi.org/10.1007/0-306-48056-5_11.
 *
 *  - [populationSize]: incumbents tracked; an accepted anchor replaces the worst.
 *  - [acceptance]: decides whether a feasible candidate joins, compared against the worst member.
 *  - [adaptivePerturbation]: raises [perturbationStrength] after [adaptiveStallThreshold] consecutive rejections,
 *    up to [maxPerturbationStrength], and lowers it on acceptance.
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
    /** Probability per restart of building the anchor by crossover of two members; needs `populationSize >= 2`. */
    val crossoverRate: Double = 0.0,
    /** How crossover picks each variable's parent. */
    val crossoverBias: CrossoverBias = CrossoverBias.Uniform,
    /** Kick applied from a feasible anchor. */
    val perturbationKind: PerturbationKind = PerturbationKind.Uniform,
    /** Crossover copies whole factor scopes, preserving co-adapted assignments. */
    val linkageAware: Boolean = false,
    // Replaces acceptance when set: arm 0 accepts, arm 1 rejects, rewarded when the population best improves by
    // the next candidate anchor.
    private val acceptanceBandit: ContextualBandit? = null,
) : RestartPolicy {

    init {
        require(populationSize >= 1) { "populationSize must be ≥ 1, got $populationSize" }
        require(crossoverRate in 0.0..1.0) { "crossoverRate must be in [0, 1], got $crossoverRate" }
    }

    // Ascending objective, index 0 best.
    private val population: MutableList<Incumbent> = ArrayList(populationSize)
    var perturbationStrength: Int = initialPerturbationStrength
        private set
    private var stallCount: Int = 0

    /** Input-change result of the last completed AllDifferent board restart; null when none completed. */
    var lastBoardRestartOutcome: BoardRestartOutcome? = null
        private set
    internal var changedBoardRestarts = 0L
        private set
    internal var declinedBoardRestarts = 0L
        private set

    // Credited at the next candidate anchor; -1 when nothing is pending.
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

    /** Drops incumbents and adaptation state so a reused policy never indexes a stale sample against another
     *  problem; learned bandit weights are kept. */
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

    // The first candidate is always accepted to seed the population.
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

    private fun acceptanceFeatures(objective: Double, worstObjective: Double, bestObjective: Double): DoubleArray {
        val vsWorst = if (worstObjective.isFinite()) (worstObjective - objective) / (abs(worstObjective) + 1.0) else 1.0
        val vsBest = if (bestObjective.isFinite()) (bestObjective - objective) / (abs(bestObjective) + 1.0) else 0.0
        val stallFrac = (stallCount.toDouble() / adaptiveStallThreshold).coerceIn(0.0, 1.0)
        return doubleArrayOf(vsWorst.coerceIn(-1.0, 1.0), vsBest.coerceIn(-1.0, 1.0), stallFrac)
    }

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        lastBoardRestartOutcome = null
        // Crossover itself diversifies, so it skips perturbation.
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

    // Incumbents captured at different arities can differ in length, so a short parent falls back to the other.
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

    // A variable in several scopes takes the last factor's pick; one in no scope takes a per-variable coin flip.
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

        /** Accept/reject contextual bandit: Bayesian linear regression with a [MultivariateGaussian] Thompson
         *  posterior. */
        fun acceptanceBandit(
            seed: Long = 0L,
            exploration: Double = 1.0,
            priorVariance: Double = 1.0,
        ): RegressionContextualBandit<*> {
            val regression = BayesianRegression(ACCEPT_FEATURES, priorVariance)
            val spec = RegressionContextualSpec(2, regression, MultivariateGaussian, exploration, regression)
            return spec.materialize(Random(seed), Concurrency.None)
        }
    }
}
