package com.eignex.klause.z3

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Z3OptimizerTest {

    @Test
    fun `z3 finds exact optimum on select`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val sample = Z3Solver(problem).minimize(objective, Z3Params()).assignment
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
        assertEquals(true, sample.bools[3])
    }

    @Test
    fun `z3 minimizes linear int cost`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val sample = Z3Solver(problem).minimize(objective, Z3Params()).assignment
        assertNotNull(sample)
        assertEquals(2, sample.ints[0])
    }

    @Test
    fun `local search and z3 agree on all different minimum`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val z3Sample = Z3Solver(problem).minimize(objective, Z3Params()).assignment
        val lsSample = LocalSearchSolver(problem)
            .minimize(objective, LocalSearchParams(maxFlips = 200_000L, randomSeed = 42L)).assignment
        assertNotNull(z3Sample)
        assertNotNull(lsSample)

        assertEquals(objective.evaluate(z3Sample), objective.evaluate(lsSample))
    }

    @Test
    fun `z3 returns null when infeasible`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
            ),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val sample = Z3Solver(problem).minimize(objective, Z3Params()).assignment
        assertEquals(null, sample)
    }
}
