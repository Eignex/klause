package com.eignex.klause.smt

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtSolverScaffoldTest {

    @Test
    fun `solves a small exactly-one over SMTInterpol`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val result = SmtSolver(problem).solve(SmtParams())
        val sat = result as? SolveResult.Sat
        assertNotNull(sat, "expected SAT verdict, got $result")
        val trueCount = sat!!.assignment.bools.count { it }
        assertEquals(1, trueCount, "exactly-one should hold; got assignment ${sat.assignment.bools.toList()}")
    }

    @Test
    fun `unsat instance returns Unsat`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.atLeastOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.atMostOne(
                    intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(0, false), Lit.make(1, false)),
                ),
            ),
        )
        // Unsat: a + b = 0 and a + b = 2.
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
        val unsatProblem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor1, factor2),
        )
        val result = SmtSolver(unsatProblem).solve(SmtParams())
        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `solve populates unsat core for tracked factor contradictions`() {
        // Two-clause direct contradiction at the factor level; SMTInterpol supports
        // GENERATE_UNSAT_CORE so we expect both factor ids to be reported.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val result = SmtSolver(problem).solve(SmtParams())
        val unsat = assertIs<SolveResult.Unsat>(result)
        val core = unsat.core
            ?: error("expected populated unsat core from SMTInterpol, got null")
        assertTrue(
            0 in core.factorIds && 1 in core.factorIds,
            "core should mention both contradicting clauses, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `assumptions force a specific value`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val result = SmtSolver(problem).solve(
            SmtParams(
                assumptions = Assumptions(bools = mapOf(2 to true)),
            ),
        )
        val sat = result as? SolveResult.Sat
        assertNotNull(sat)
        assertEquals(true, sat!!.assignment.bools[2])
        assertEquals(false, sat.assignment.bools[0])
        assertEquals(false, sat.assignment.bools[1])
    }
}
