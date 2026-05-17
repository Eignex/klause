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
        assertTrue(AcceptanceCriterion.Improving.accept(1.0, 2.0))
        assertTrue(!AcceptanceCriterion.Improving.accept(2.0, 2.0))
        assertTrue(!AcceptanceCriterion.Improving.accept(3.0, 2.0))

        assertTrue(AcceptanceCriterion.BetterOrEqual.accept(1.0, 2.0))
        assertTrue(AcceptanceCriterion.BetterOrEqual.accept(2.0, 2.0))
        assertTrue(!AcceptanceCriterion.BetterOrEqual.accept(3.0, 2.0))

        assertTrue(AcceptanceCriterion.RandomWalk.accept(1.0, 2.0))
        assertTrue(AcceptanceCriterion.RandomWalk.accept(2.0, 2.0))
        assertTrue(AcceptanceCriterion.RandomWalk.accept(3.0, 2.0))
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
