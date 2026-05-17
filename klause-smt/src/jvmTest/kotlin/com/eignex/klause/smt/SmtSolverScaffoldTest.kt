package com.eignex.klause.smt

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtSolverScaffoldTest {

    @Test
    fun `solves a small exactly-one over SMTInterpol`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        val problem = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(factor))
        val result = SmtSolver(problem).solve(SmtParams())
        val sat = result as? SolveResult.Sat
        assertNotNull(sat, "expected SAT verdict, got $result")
        val trueCount = sat!!.assignment.bools.count { it }
        assertEquals(1, trueCount, "exactly-one should hold; got assignment ${sat.assignment.bools.toList()}")
    }

    @Test
    fun `unsat instance returns Unsat`() {
        // AtMost(0) AND AtLeast(1) — contradictory.
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(0, false), Lit.make(1, false))),
            ))
        // The atMost(1) over {x0, x1, ¬x0, ¬x1} forces at most one to be true; but
        // x0 + ¬x0 = 1 always, so the atMost-1 is already satisfied. atLeast(1) on
        // x0 ∨ x1 just needs one true. So this isn't actually unsat; pick a simpler test.
        // Simpler unsat: a + b = 0 and a + b = 2.
        val factor1 = com.eignex.klause.solver.factor.PseudoBoolean(
            weights = intArrayOf(1, 1),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            op = com.eignex.klause.ast.PbOp.EQ,
            bound = 0,
        )
        val factor2 = com.eignex.klause.solver.factor.PseudoBoolean(
            weights = intArrayOf(1, 1),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            op = com.eignex.klause.ast.PbOp.EQ,
            bound = 2,
        )
        val unsatProblem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(factor1, factor2))
        val result = SmtSolver(unsatProblem).solve(SmtParams())
        assertEquals(SolveResult.Unsat, result)
    }

    @Test
    fun `assumptions force a specific value`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        val problem = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(factor))
        val result = SmtSolver(problem).solve(SmtParams(
            assumptions = Assumptions(bools = mapOf(2 to true)),
        ))
        val sat = result as? SolveResult.Sat
        assertNotNull(sat)
        assertEquals(true, sat!!.assignment.bools[2])
        assertEquals(false, sat.assignment.bools[0])
        assertEquals(false, sat.assignment.bools[1])
    }
}
