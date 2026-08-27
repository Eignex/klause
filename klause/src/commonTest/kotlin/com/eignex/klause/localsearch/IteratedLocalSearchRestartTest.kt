package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.CrossoverBias.BetterBiased
import com.eignex.klause.solver.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IteratedLocalSearchRestartTest {

    @Test
    fun `each acceptance criterion accepts candidates per its semantics`() {
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
    fun `SA acceptance always accepts improvements and cools toward Improving`() {
        val sa = AcceptanceCriterion.SimulatedAnnealing(
            initialTemperature = 1e6,
            coolingRate = 0.5,
            minTemperature = 1e-9,
        )
        val rng = Random(0)
        var acceptedWorse = 0
        repeat(10) { if (sa.accept(10.0, 1.0, rng)) acceptedWorse++ }
        repeat(20) { assertTrue(sa.accept(1.0, 10.0, rng)) }
        assertTrue(acceptedWorse > 0, "expected some worsening acceptances at high T")
        var rejectedAtLowT = 0
        repeat(50) { if (!sa.accept(10.0, 1.0, rng)) rejectedAtLowT++ }
        assertTrue(rejectedAtLowT >= 45, "at min T, worsening should reject; got $rejectedAtLowT/50")
    }

    @Test
    fun `SA temperature respects min floor`() {
        val sa = AcceptanceCriterion.SimulatedAnnealing(
            initialTemperature = 1.0,
            coolingRate = 0.001,
            minTemperature = 0.5,
        )
        val rng = Random(0)
        repeat(100) { sa.accept(0.0, 0.0, rng) }
        assertTrue(sa.temperature >= 0.5, "temperature escaped min floor: ${sa.temperature}")
    }

    @Test
    fun `SA reset restores initial temperature`() {
        val sa = AcceptanceCriterion.SimulatedAnnealing(
            initialTemperature = 2.0,
            coolingRate = 0.5,
            minTemperature = 0.01,
        )
        val rng = Random(0)
        repeat(10) { sa.accept(0.0, 0.0, rng) }
        assertTrue(sa.temperature < 2.0, "expected cooling, got ${sa.temperature}")
        sa.reset()
        assertEquals(2.0, sa.temperature)
    }

    @Test
    fun `restart falls back to random when no local optimum seen`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart()
        policy.restart(state, bestSoFar = null)
        assertTrue(state.step == 0L, "restart should reset step to 0")
    }

    @Test
    fun `incumbent updates on improving local optimum`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 3)
        val s1 = Sample(booleanArrayOf(true, false), longArrayOf())
        val s2 = Sample(booleanArrayOf(false, true), longArrayOf())
        policy.onLocalOptimum(state, s1, objective = 10.0)
        policy.onLocalOptimum(state, s2, objective = 5.0)
        policy.onLocalOptimum(state, s1, objective = 10.0)

        // Adaptive strength ramps on stalls only; one stall is below the default threshold of 3.
        assertEquals(3, policy.perturbationStrength, "no bump until threshold reached")

        repeat(3) { policy.onLocalOptimum(state, s1, objective = 10.0) }
        assertTrue(policy.perturbationStrength > 3, "expected adaptive bump, got ${policy.perturbationStrength}")
    }

    @Test
    fun `population fills up to size and evicts worst on accept`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(
            populationSize = 3,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 10.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 8.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 12.0)
        assertEquals(3, policy.incumbents.size, "population should be at capacity")
        assertEquals(8.0, policy.incumbents[0].objective)
        assertEquals(12.0, policy.incumbents.last().objective)

        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 5.0)
        assertEquals(3, policy.incumbents.size, "size capped")
        assertEquals(5.0, policy.incumbents[0].objective, "5.0 should be new best")
        assertEquals(10.0, policy.incumbents.last().objective, "12.0 should have been evicted")

        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 11.0)
        assertEquals(3, policy.incumbents.size)
        assertEquals(10.0, policy.incumbents.last().objective, "population unchanged on reject")
    }

    @Test
    fun `single incumbent mode preserved by default`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart()
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 10.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(false, true), longArrayOf()), 5.0)
        assertEquals(1, policy.incumbents.size, "single-incumbent mode")
        assertEquals(5.0, policy.incumbents[0].objective)
    }

    @Test
    fun `crossover restart with two anchors produces a mix of their values`() {
        val factor = Cardinality.atLeastOne(IntArray(8) { Lit.make(it, true) })
        val problem = Problem(8, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(42))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(
            populationSize = 2,
            crossoverRate = 1.0,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        policy.onLocalOptimum(state, Sample(BooleanArray(8) { true }, LongArray(0)), 10.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(8) { false }, LongArray(0)), 12.0)

        policy.restart(state, bestSoFar = null)
        val trues = (0 until 8).count { state.assignment.boolValue(it) }
        assertTrue(
            trues in 1..7,
            "expected mixed assignment after crossover, got $trues true (8 bits, both parents pure)",
        )
    }

    @Test
    fun `crossover survives incumbents whose arrays differ in length`() {
        val factor = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(4), LongArray(3)), 10.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(4), LongArray(2)), 12.0)

        repeat(20) { policy.restart(state, bestSoFar = null) }
    }

    @Test
    fun `crossover bias computes probParentA per its policy`() {
        val betterBiased = BetterBiased(rate = 0.5)
        val cases = listOf(
            Triple(betterBiased, 1.0 to 10.0, 1.0),
            Triple(betterBiased, 10.0 to 1.0, 0.0),
            Triple(betterBiased, 5.0 to 5.0, 0.5),
            Triple(CrossoverBias.Uniform, 1.0 to 100.0, 0.5),
            Triple(CrossoverBias.Uniform, 100.0 to 1.0, 0.5),
        )
        for ((bias, objectives, expected) in cases) {
            val (a, b) = objectives
            assertEquals(expected, bias.probParentA(a, b), "$bias parentA=$a parentB=$b")
        }
    }

    @Test
    fun `reset clears incumbents and restores perturbation strength`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 3, initialPerturbationStrength = 3)
        repeat(6) { policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), longArrayOf()), 10.0) }
        assertTrue(policy.incumbents.isNotEmpty(), "population should have filled")
        assertTrue(policy.perturbationStrength > 3, "expected an adaptive bump before reset")

        policy.reset()
        assertEquals(0, policy.incumbents.size, "reset clears the population")
        assertEquals(3, policy.perturbationStrength, "reset restores the initial perturbation strength")
    }
}
