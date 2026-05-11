package com.eignex.klause.solver

import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OptimizerTest {

    /** ExactlyOne over 4 bools with weights (10, 5, 8, 3): pick bool 3 (weight 3). */
    @Test
    fun `local search optimizer picks min weight single select`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem)
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 50_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
        for (i in 0..3) {
            assertEquals(i == 3, sample.bools[i], "bool $i should be ${i == 3}")
        }
    }

    /** Linear cost on int: minimize 1·x subject to x ≥ 2 over [0..5]. Optimum: x=2. */
    @Test
    fun `local search optimizer minimizes linear int cost`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(com.eignex.klause.solver.factor.IntGeq(intVar = 0, bound = 2)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L))
        assertNotNull(sample)
        assertEquals(2, sample.ints[0])
    }

    /** Permutation of [0..3] over 4 ints, minimize sum: optimum is 0+1+2+3 = 6 — but with
     *  AllDifferent the only assignments are permutations, so any permutation has sum 6.
     *  Bias the objective so descent has direction: minimize 1·x[0] + 2·x[1] + 3·x[2] + 4·x[3].
     *  Optimum: x[0]=3, x[1]=2, x[2]=1, x[3]=0 → 1·3 + 2·2 + 3·1 + 4·0 = 3 + 4 + 3 + 0 = 10. */
    @Test
    fun `local search optimizer on all different`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 13L))
        assertNotNull(sample)
        // The lowest-weighted slot should hold the highest value.
        val score = objective.evaluate(sample)
        assertTrue(score <= 10.0, "expected optimum ≤ 10, got $score for ${sample.ints.toList()}")
    }

    @Test
    fun `linear objective evaluation`() {
        val obj = LinearObjective(
            boolWeights = doubleArrayOf(2.0, -1.0, 3.0),
            intCoefficients = doubleArrayOf(0.5),
            constant = 10.0,
        )
        val s = Sample(bools = booleanArrayOf(true, true, false), ints = intArrayOf(4))
        // 10 + (2·1 + (-1)·1 + 3·0) + (0.5·4) = 10 + 1 + 2 = 13
        assertEquals(13.0, obj.evaluate(s))
    }

    /** Unsatisfiable problem: no minimum, returns null. */
    @Test
    fun `local search optimizer returns null when infeasible`() {
        val problem = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 0L))
        assertEquals(null, sample)
    }
}
