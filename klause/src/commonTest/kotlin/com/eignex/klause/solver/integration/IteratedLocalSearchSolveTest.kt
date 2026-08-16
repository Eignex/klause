package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IteratedLocalSearchSolveTest {

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

        // Reusing the same policy on an 8-bool problem: without a reset the first restart's crossover
        // sizes the child from a zero-length stale parent and indexes it against 8 bool vars → AIOOBE.
        val problem = Problem(
            8,
            0,
            emptyArray(),
            listOf(Cardinality.atLeastOne(IntArray(8) { Lit.make(it, true) })),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = policy)
        val result = solver.solve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        assertTrue(result is SolveResult.Sat, "solve completes without indexing a stale incumbent")
    }

    @Test
    fun `engine resets a reused policy on the minimize path so a stale incumbent cannot leak`() {
        // The minimize (COP) path builds its state via newMinimizeState; taking its first restart
        // without resetting a reused policy would anchor a prior solve's stale (wrong-arity)
        // incumbent and index it against this problem.
        val policy = IteratedLocalSearchRestart(populationSize = 2, maxFlipsBeforeRestart = 5)
        val seedProblem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            factors = emptyArray(),
        )
        val seedState = LocalSearchState(seedProblem, Random(0))
        for (i in 0 until seedProblem.numFactors) seedState.factors[i].initialize(seedState, i)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(2)), 10.0)
        policy.onLocalOptimum(seedState, Sample(BooleanArray(0), LongArray(2)), 12.0)
        assertEquals(2, policy.incumbents.size, "seeded stale incumbents")

        val n = 6
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L))
        val sample = LocalSearchSolver(problem.bake(), restartPolicy = policy)
            .minimize(objective, LocalSearchParams(maxFlips = 6_000L, randomSeed = 1L)).assignment
        assertNotNull(sample, "minimize completes without anchoring a stale incumbent")
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
        val solver = LocalSearchSolver(
            problem.bake(),
            restartPolicy = IteratedLocalSearchRestart(maxFlipsBeforeRestart = 50),
        )
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 4_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }
}
