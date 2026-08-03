package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DomWdegTest {

    @Test
    fun `dom-wdeg finds SAT and proves UNSAT on small instances`() {
        // Mixed sanity check: SAT + UNSAT problems both terminate correctly under
        // dom/wdeg picking.
        val satProblem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
        )
        val r1 = BacktrackSolver(satProblem.bake()).solve(
            BacktrackParams(
                variableSelector = DomWdeg(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r1)
        assertEquals(1, sat.assignment.bools.count { it })

        val unsatProblem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r2 = BacktrackSolver(unsatProblem.bake()).solve(
            BacktrackParams(
                variableSelector = DomWdeg(),
            ),
        )
        assertIs<SolveResult.Unsat>(r2)
    }
}
