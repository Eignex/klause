package com.eignex.klause.solver

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver

import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OptimizerTest {

    @Test
    fun `local search optimizer picks min weight single select`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem)
        val sample = solver.minimize(objective, LocalSearchParams(maxFlips = 50_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
        for (i in 0..3) {
            assertEquals(i == 3, sample.bools[i], "bool $i should be ${i == 3}")
        }
    }

    @Test
    fun `local search optimizer minimizes linear int cost`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(com.eignex.klause.solver.factor.Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L)).assignment
        assertNotNull(sample)
        assertEquals(2, sample.ints[0])
    }

    @Test
    fun `local search optimizer on all different`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 13L)).assignment
        assertNotNull(sample)

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

        assertEquals(13.0, obj.evaluate(s))
    }

    @Test
    fun `local search optimizer returns null when infeasible`() {
        val problem = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0))
        val sample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 0L)).assignment
        assertEquals(null, sample)
    }
}
