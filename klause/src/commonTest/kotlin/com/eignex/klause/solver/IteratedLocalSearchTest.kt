package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.factor.Cardinality
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
        val sa = AcceptanceCriterion.SimulatedAnnealing(initialTemperature = 1e6, coolingRate = 0.5, minTemperature = 1e-9)
        val rng = Random(0)
        // High T → worsening accepted (high probability).
        var acceptedWorse = 0
        repeat(10) { if (sa.accept(10.0, 1.0, rng)) acceptedWorse++ }
        // Strict improvements always accepted regardless of T.
        repeat(20) { assertTrue(sa.accept(1.0, 10.0, rng)) }
        assertTrue(acceptedWorse > 0, "expected some worsening acceptances at high T")
        // Now T is very small — worsening should almost always reject.
        var rejectedAtLowT = 0
        repeat(50) { if (!sa.accept(10.0, 1.0, rng)) rejectedAtLowT++ }
        assertTrue(rejectedAtLowT >= 45, "at min T, worsening should reject; got $rejectedAtLowT/50")
    }

    @Test
    fun `SA temperature respects min floor`() {
        val sa = AcceptanceCriterion.SimulatedAnnealing(initialTemperature = 1.0, coolingRate = 0.001, minTemperature = 0.5)
        val rng = Random(0)
        repeat(100) { sa.accept(0.0, 0.0, rng) }
        assertTrue(sa.temperature >= 0.5, "temperature escaped min floor: ${sa.temperature}")
    }

    @Test
    fun `SA reset restores initial temperature`() {
        val sa = AcceptanceCriterion.SimulatedAnnealing(initialTemperature = 2.0, coolingRate = 0.5, minTemperature = 0.01)
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
        // bestSoFar = null, incumbent = null → should fall through to state.restart().
        policy.restart(state, bestSoFar = null)
        // No assertion failures = state.restart() didn't throw; state is in a valid post-restart shape.
        assertTrue(state.step == 0L, "restart should reset step to 0")
    }

    @Test
    fun `incumbent updates on improving local optimum`() {
        val factor = Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(initialPerturbationStrength = 3)
        val s1 = Sample(booleanArrayOf(true, false), intArrayOf())
        val s2 = Sample(booleanArrayOf(false, true), intArrayOf())
        policy.onLocalOptimum(state, s1, objective = 10.0)
        policy.onLocalOptimum(state, s2, objective = 5.0) // strictly better → accept
        policy.onLocalOptimum(state, s1, objective = 10.0) // worse → reject, stall++

        // Two strict improvements should not change the adaptive perturbation strength
        // upward (it only ramps on STALLS, not improvements). The single rejection bumps
        // stallCount to 1, below default threshold 3 — so still initial.
        assertEquals(3, policy.perturbationStrength, "no bump until threshold reached")

        repeat(3) { policy.onLocalOptimum(state, s1, objective = 10.0) }
        // Now stallCount has crossed threshold at least once; strength must have bumped up.
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
            acceptance = com.eignex.klause.solver.localsearch.AcceptanceCriterion.BetterOrEqual,
        )
        val s = { obj: Double -> Sample(booleanArrayOf(true, false), intArrayOf()) to obj }
        policy.onLocalOptimum(state, s(10.0).first, 10.0)
        policy.onLocalOptimum(state, s(8.0).first, 8.0)
        policy.onLocalOptimum(state, s(12.0).first, 12.0)
        assertEquals(3, policy.incumbents.size, "population should be at capacity")
        // Objectives are sorted ascending; population is {8, 10, 12}.
        assertEquals(8.0, policy.incumbents[0].objective)
        assertEquals(12.0, policy.incumbents.last().objective)

        // A new optimum at 5.0 beats the worst (12.0) — should be admitted, worst evicted.
        policy.onLocalOptimum(state, s(5.0).first, 5.0)
        assertEquals(3, policy.incumbents.size, "size capped")
        assertEquals(5.0, policy.incumbents[0].objective, "5.0 should be new best")
        assertEquals(10.0, policy.incumbents.last().objective, "12.0 should have been evicted")

        // A new optimum at 11.0 doesn't beat the worst (10.0) — rejected under BetterOrEqual.
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

        val policy = IteratedLocalSearchRestart() // populationSize defaults to 1
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, false), intArrayOf()), 10.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(false, true), intArrayOf()), 5.0)
        // Default acceptance is Improving — 5.0 < 10.0 admitted, evicting 10.0.
        assertEquals(1, policy.incumbents.size, "single-incumbent mode")
        assertEquals(5.0, policy.incumbents[0].objective)
    }

    @Test
    fun `crossover restart with two anchors produces a mix of their values`() {
        // Two clearly-distinguishable parents: all-true vs all-false. Uniform crossover
        // should give a mix (Hamming distance to each parent > 0 and < numVars with high prob).
        val factor = Cardinality.atLeastOne(IntArray(8) { Lit.make(it, true) })
        val problem = Problem(8, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(42))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(
            populationSize = 2,
            crossoverRate = 1.0, // force crossover every restart
            acceptance = com.eignex.klause.solver.localsearch.AcceptanceCriterion.BetterOrEqual,
        )
        policy.onLocalOptimum(state, Sample(BooleanArray(8) { true }, IntArray(0)), 10.0)
        policy.onLocalOptimum(state, Sample(BooleanArray(8) { false }, IntArray(0)), 12.0)

        policy.restart(state, bestSoFar = null)
        // Sum trues should be between 1 and 7 with very high probability under uniform crossover.
        val trues = (0 until 8).count { state.assignment.boolValue(it) }
        assertTrue(trues in 1..7,
            "expected mixed assignment after crossover, got $trues true (8 bits, both parents pure)")
    }

    @Test
    fun `crossover does nothing when population has fewer than 2 incumbents`() {
        // Single-member population — crossover should silently fall back to single-anchor mode.
        val factor = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)

        val policy = IteratedLocalSearchRestart(populationSize = 3, crossoverRate = 1.0)
        policy.onLocalOptimum(state, Sample(booleanArrayOf(true, true, true, true), IntArray(0)), 4.0)
        // Only one incumbent — crossover requires two. Restart should perturb the lone anchor
        // (or fall back to random if anchor=null). No crash.
        policy.restart(state, bestSoFar = null)
        // Just verify the engine didn't blow up; the resulting state is whatever perturbation produced.
        assertTrue(state.step == 0L, "restart should reset step")
    }

    @Test
    fun `ils restart solves exact one cardinality`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem, restartPolicy = IteratedLocalSearchRestart(maxFlipsBeforeRestart = 50))
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 20_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
