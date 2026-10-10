package com.eignex.klause.localsearch
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
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
    fun `population fills up to size and evicts worst on accept`() {
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
    fun `crossover survives incumbents whose arrays differ in length`() {
        val factor = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(4), LongArray(3)), 10.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(4), LongArray(2)), 12.0)

        repeat(20) { policy.restart(state, bestSoFar = null) }
    }

    @Test
    fun `reset clears incumbents and restores perturbation strength`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
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
