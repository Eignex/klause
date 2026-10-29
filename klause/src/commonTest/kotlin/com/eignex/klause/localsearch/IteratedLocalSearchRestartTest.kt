package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IteratedLocalSearchRestartTest {
    @Test
    fun `crossover restarts should clear tabu tenure and preserve write activity`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.apply(Move.BoolFlip(0))
        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0)
        val sample = state.assignment.snapshot()
        policy.onLocalOptimum(state, sample, 0.0)
        policy.onLocalOptimum(state, sample, 1.0)

        policy.restart(state, sample)

        assertFalse(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 10))
        assertEquals(1, state.tabu.touchCount[0])
    }

    @Test
    fun `feasible stalls should use the same anchor acceptance and strength adaptation`() {
        val state = LocalSearchState(Problem(1, 0, emptyArray(), emptyArray()).bake(), Random(0))
        val completed = IteratedLocalSearchRestart(populationSize = 2, initialPerturbationStrength = 3)
        val stalled = IteratedLocalSearchRestart(populationSize = 2, initialPerturbationStrength = 3)
        val sample = Sample(booleanArrayOf(true), longArrayOf())

        for (objective in listOf(10.0, 8.0, 12.0, 12.0, 12.0, 5.0)) {
            completed.onLocalOptimum(state, sample, objective)
            stalled.onFeasibleStall(state, sample, objective)
        }

        assertEquals(completed.incumbents, stalled.incumbents)
        assertEquals(completed.perturbationStrength, stalled.perturbationStrength)
    }

    @Test
    fun `each acceptance criterion should accept candidates per its semantics`() {
        val rng = Random(0)
        val cases = listOf(
            Triple(AcceptanceCriterion.Improving, 1.0 to 2.0, true),
            Triple(AcceptanceCriterion.Improving, 2.0 to 2.0, false),
            Triple(AcceptanceCriterion.Improving, 3.0 to 2.0, false),
            Triple(AcceptanceCriterion.BetterOrEqual, 1.0 to 2.0, true),
            Triple(AcceptanceCriterion.BetterOrEqual, 2.0 to 2.0, true),
            Triple(AcceptanceCriterion.BetterOrEqual, 3.0 to 2.0, false),
            Triple(AcceptanceCriterion.RandomWalk, 1.0 to 2.0, true),
            Triple(AcceptanceCriterion.RandomWalk, 2.0 to 2.0, true),
            Triple(AcceptanceCriterion.RandomWalk, 3.0 to 2.0, true),
        )
        for ((criterion, candidateAndBaseline, expected) in cases) {
            val (candidate, baseline) = candidateAndBaseline
            assertEquals(
                expected,
                criterion.accept(candidate, baseline, rng),
                "$criterion candidate=$candidate baseline=$baseline",
            )
        }
    }

    @Test
    fun `population should fill up to size and evict the worst on accept`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(
            populationSize = 3,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 10.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 8.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 12.0)
        assertEquals(3, policy.incumbents.size)
        assertEquals(8.0, policy.incumbents[0].objective)
        assertEquals(12.0, policy.incumbents.last().objective)

        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 5.0)
        assertEquals(3, policy.incumbents.size)
        assertEquals(5.0, policy.incumbents[0].objective, "5.0 should be new best")
        assertEquals(10.0, policy.incumbents.last().objective, "12.0 should have been evicted")

        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 11.0)
        assertEquals(3, policy.incumbents.size)
        assertEquals(10.0, policy.incumbents.last().objective)
    }

    @Test
    fun `reset should clear incumbents and restore perturbation strength`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 3, initialPerturbationStrength = 3)
        repeat(6) { policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 10.0) }
        assertTrue(policy.incumbents.isNotEmpty())
        assertTrue(policy.perturbationStrength > 3, "expected an adaptive bump before reset")

        policy.reset()
        assertEquals(0, policy.incumbents.size)
        assertEquals(3, policy.perturbationStrength)
    }

    @Test
    fun `solve should reset a reused restart policy`() {
        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0, maxFlipsBeforeRestart = 5)
        val seedProblem = Problem(
            4,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })),
        )
        val seedState = LocalSearchState(seedProblem.bake(), Random(0))
        for (i in 0 until seedProblem.numFactors) seedState.factors[i].initialize(seedState, i)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(0)), 10.0)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(0)), 12.0)
        assertEquals(2, policy.incumbents.size)

        val problem = Problem(
            8,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(IntArray(8) { Lit.make(it, true) })),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = policy)
        val result = solver.solve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        assertTrue(result is SolveResult.Sat)
    }

    @Test
    fun `minimize should reset a reused restart policy`() {
        val policy = IteratedLocalSearchRestart(populationSize = 2, maxFlipsBeforeRestart = 5)
        val seedProblem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), emptyArray())
        val seedState = LocalSearchState(seedProblem.bake(), Random(0))
        for (i in 0 until seedProblem.numFactors) seedState.factors[i].initialize(seedState, i)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(2)), 10.0)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(2)), 12.0)
        val n = 6
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L))

        val result = LocalSearchSolver(problem.bake(), restartPolicy = policy)
            .minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 1L))

        assertNotNull(result.assignment)
    }

    @Test
    fun `iterated local search restart should minimize over an exactly-one constraint`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val solver = LocalSearchSolver(
            problem.bake(),
            restartPolicy = IteratedLocalSearchRestart(maxFlipsBeforeRestart = 50),
        )
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 500L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
