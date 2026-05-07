package com.eignex.klause.solver

import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestartPolicyTest {

    @Test
    fun fixedCadenceTriggersAtBoundary() {
        val p = FixedCadenceRestart(maxFlipsBeforeRestart = 100)
        assertEquals(false, p.shouldRestart(0))
        assertEquals(false, p.shouldRestart(99))
        assertEquals(true, p.shouldRestart(100))
        assertEquals(true, p.shouldRestart(1_000_000))
    }

    @Test
    fun adaptivePerturbationFallsBackWhenNoBest() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.restart()
        // Set a known assignment so we can detect that fallback randomises it.
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)

        AdaptivePerturbationRestart().restart(state, bestSoFar = null)
        // Falling back to state.restart() randomises the assignment; the bool values are
        // now drawn from rng. We can't predict the exact result, but the recompute must
        // have run (hardCost reflects the fresh assignment).
        // Indirect check: hardCost is well-defined and consistent with the current bools.
        val countTrue = (0..2).count { state.assignment.boolValue(it) }
        val expectedHard = if (countTrue == 1) 0 else 1
        assertEquals(expectedHard, state.hardCost)
    }

    @Test
    fun adaptivePerturbationAnchorsToBestThenPerturbs() {
        // 6-bool problem, no factors — hardCost is always 0 so we can isolate the
        // perturbation-distance check.
        val problem = Problem(6, 0, emptyArray(), emptyList())
        val state = SolverState(problem, Random(0))
        state.restart()
        for (b in 0..5) state.assignment.setBool(b, false)

        val best = Sample(bools = booleanArrayOf(true, true, true, true, true, true), ints = intArrayOf())
        val policy = AdaptivePerturbationRestart(perturbationStrength = 2)
        policy.restart(state, bestSoFar = best)

        // Anchored to bestSoFar, then 2 random vars perturbed. So the result differs from
        // bestSoFar in at most 2 positions (could be 0, 1, or 2 depending on whether the
        // perturbation hit the same var twice).
        val differences = (0..5).count { state.assignment.boolValue(it) != best.bools[it] }
        assertTrue(differences in 0..2,
            "perturbed assignment differs from bestSoFar in $differences positions, expected 0..2")
    }

    @Test
    fun adaptivePerturbationRestartIntegratesWithLocalSearchOptimizer() {
        // Permutation problem — small enough for the optimiser to find the global optimum.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        // Same problem with two different restart policies. Both should land on the same
        // optimum given the budget — adaptive perturbation just gets there faster on
        // harder problems; on this one they tie.
        val fixed = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart())
        val adaptive = LocalSearchSolver(problem, restartPolicy = AdaptivePerturbationRestart())

        val a = fixed.minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 1L))
        val b = adaptive.minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 1L))
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(objective.evaluate(a), objective.evaluate(b))
    }
}
