package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.CrossoverBias.BetterBiased
import com.eignex.klause.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IteratedLocalSearchTest {

    @Test
    fun `acceptance criteria semantics`() {
        val rng = Random(0)
        assertTrue(AcceptanceCriterion.Improving.accept(1.0, 2.0, rng))
        assertTrue(!AcceptanceCriterion.Improving.accept(2.0, 2.0, rng))
        assertTrue(!AcceptanceCriterion.Improving.accept(3.0, 2.0, rng))

        assertTrue(AcceptanceCriterion.BetterOrEqual.accept(1.0, 2.0, rng))
        assertTrue(AcceptanceCriterion.BetterOrEqual.accept(2.0, 2.0, rng))
        assertTrue(!AcceptanceCriterion.BetterOrEqual.accept(3.0, 2.0, rng))

        assertTrue(AcceptanceCriterion.RandomWalk.accept(1.0, 2.0, rng))
        assertTrue(AcceptanceCriterion.RandomWalk.accept(2.0, 2.0, rng))
        assertTrue(AcceptanceCriterion.RandomWalk.accept(3.0, 2.0, rng))
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
            acceptance = com.eignex.klause.localsearch.AcceptanceCriterion.BetterOrEqual,
        )
        val s = { obj: Double -> Sample(booleanArrayOf(true, false), longArrayOf()) to obj }
        policy.onLocalOptimum(state, s(10.0).first, 10.0)
        policy.onLocalOptimum(state, s(8.0).first, 8.0)
        policy.onLocalOptimum(state, s(12.0).first, 12.0)
        assertEquals(3, policy.incumbents.size, "population should be at capacity")
        assertEquals(8.0, policy.incumbents[0].objective)
        assertEquals(12.0, policy.incumbents.last().objective)

        policy.onLocalOptimum(state, s(5.0).first, 5.0)
        assertEquals(3, policy.incumbents.size, "size capped")
        assertEquals(5.0, policy.incumbents[0].objective, "5.0 should be new best")
        assertEquals(10.0, policy.incumbents.last().objective, "12.0 should have been evicted")

        policy.onLocalOptimum(state, s(11.0).first, 11.0)
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
            acceptance = com.eignex.klause.localsearch.AcceptanceCriterion.BetterOrEqual,
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
    fun `BetterBiased crossover skews toward the better parent`() {
        val bias = BetterBiased(rate = 0.5)
        assertEquals(
            1.0,
            bias.probParentA(parentAObjective = 1.0, parentBObjective = 10.0),
            "fully-biased should pick A when A is better",
        )
        assertEquals(
            0.0,
            bias.probParentA(parentAObjective = 10.0, parentBObjective = 1.0),
            "fully-biased should pick B when B is better",
        )
        assertEquals(
            0.5,
            bias.probParentA(parentAObjective = 5.0, parentBObjective = 5.0),
            "tied parents should fall back to uniform",
        )
    }

    @Test
    fun `Uniform crossover ignores objective`() {
        val bias = com.eignex.klause.localsearch.CrossoverBias.Uniform
        assertEquals(0.5, bias.probParentA(1.0, 100.0))
        assertEquals(0.5, bias.probParentA(100.0, 1.0))
    }

    @Test
    fun `crossover does nothing when population has fewer than 2 incumbents`() {
        val factor = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 3, crossoverRate = 1.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, true, true, true), LongArray(0)), 4.0)
        policy.restart(state, bestSoFar = null)
        assertTrue(state.step == 0L, "restart should reset step")
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

    @Test
    fun `engine resets a reused policy so a stale incumbent cannot leak across solves`() {
        // Mimic a prior integer-only solve (numBoolVars == 0) that left zero-bool incumbents in a
        // policy instance the tuning harness then reuses on a Boolean problem.
        val policy = IteratedLocalSearchRestart(populationSize = 2, crossoverRate = 1.0, maxFlipsBeforeRestart = 5)
        val seedProblem = Problem(
            4,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })),
        )
        val seedState = LocalSearchState(seedProblem, Random(0))
        for (i in 0 until seedProblem.numFactors) seedState.factors[i].initialize(seedState, i)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(0)), 10.0)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(0)), 12.0)
        assertEquals(2, policy.incumbents.size, "seeded stale incumbents")

        // Reusing the same policy on an 8-bool problem: before the fix, the first restart's crossover
        // sizes the child from a zero-length stale parent and indexes it against 8 bool vars → AIOOBE.
        val problem = Problem(
            8,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(IntArray(8) { Lit.make(it, true) })),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = policy)
        val result = solver.solve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        assertTrue(result is SolveResult.Sat, "solve completes without indexing a stale incumbent")
    }

    @Test
    fun `ils restart solves exact one cardinality`() {
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
        val solver = LocalSearchSolver(problem, restartPolicy = IteratedLocalSearchRestart(maxFlipsBeforeRestart = 50))
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 4_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
