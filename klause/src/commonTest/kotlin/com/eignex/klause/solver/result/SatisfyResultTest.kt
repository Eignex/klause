package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SatisfyResultTest {

    @Test
    fun `satisfy should return sat under consistent assumptions`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = BacktrackSolver(problem.bake()).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true)),
            BacktrackParams(),
        )
        val sat = assertIs<SatisfyResult.Sat>(r)
        assertEquals(true, sat.sample.bools[0])
    }

    @Test
    fun `satisfy should project a seed conflict onto the conflicting assumptions`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, positive = true)))),
        )
        val r = BacktrackSolver(problem.bake()).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to false, 2 to true)),
            BacktrackParams(),
        )
        val unsat = assertIs<SatisfyResult.UnsatUnderAssumptions>(r)
        val core = unsat.core
        assertTrue(0 in core.boolKeys.toList())
        assertEquals(false, core.boolValueOrNull(0))
        assertTrue(2 !in core.boolKeys.toList())
    }

    @Test
    fun `satisfy should return globally unsat without assumptions`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, positive = true))),
                Clause(intArrayOf(Lit.make(0, positive = false))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).satisfyUnderAssumptions(
            Assumptions.None,
            BacktrackParams(),
        )
        assertIs<SatisfyResult.GloballyUnsat>(r)
    }

    @Test
    fun `solve should populate the assumption core from the failing pins`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = mapOf(0 to true, 1 to true, 2 to true)),
            ),
        )
        val unsat = assertIs<SolveResult.Unsat>(r)
        val ac = unsat.assumptionCore
        assertTrue(ac != null && !ac.isEmpty)
        assertEquals(true, ac.boolValueOrNull(0))
        assertEquals(true, ac.boolValueOrNull(1))
        assertTrue(2 !in ac.boolKeys.toList())
    }

    @Test
    fun `satisfy should include both pairwise conflicting pins in the core`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, positive = false), Lit.make(1, positive = false))),
                Clause(intArrayOf(Lit.make(0, positive = true), Lit.make(1, positive = true))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true, 1 to true)),
            BacktrackParams(),
        )
        val unsat = assertIs<SatisfyResult.UnsatUnderAssumptions>(r)
        assertEquals(true, unsat.core.boolValueOrNull(0))
        assertEquals(true, unsat.core.boolValueOrNull(1))
    }
}
