package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AssumptionsTest {

    @Test
    fun `minimize should respect bool assumption`() {
        // 4 bools, objective rewards every true; without assumptions optimal is "all true".
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val solver = LocalSearchSolver(problem.bake())
        val obj = LinearObjective(boolWeights = longArrayOf(-1L, -1L, -1L, -1L))
        val sample = solver.minimize(
            obj,
            LocalSearchParams(
                randomSeed = 3L,
                maxFlips = 50_000L,
                assumptions = Assumptions(bools = mapOf(2 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(false, sample.bools[2], "bool 2 must stay false despite negative weight")
        assertEquals(true, sample.bools[0])
        assertEquals(true, sample.bools[1])
        assertEquals(true, sample.bools[3])
    }

    @Test
    fun `assumptions should compose with hard constraints`() {
        // Clause: bool0 OR bool1. Assume bool0 = false → bool1 must be true.
        val clauses = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = clauses)
        val solver = LocalSearchSolver(problem.bake())
        val sample = solver.sample(
            LocalSearchParams(
                randomSeed = 9L,
                maxFlips = 10_000,
                assumptions = Assumptions(bools = mapOf(0 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(false, sample.bools[0])
        assertEquals(true, sample.bools[1])
    }
}
