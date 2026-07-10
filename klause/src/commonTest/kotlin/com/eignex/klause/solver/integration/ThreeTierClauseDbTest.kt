package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Three-tier learned-clause DB (#201). Forgetting learned clauses is always sound (they are
 * redundant), so the tiered reduction must never change a verdict or the feasible set — even
 * under an aggressively small cap that forces frequent reductions and tier churn.
 */
class ThreeTierClauseDbTest {

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    factors.add(Clause(intArrayOf(Lit.make(v(p1, h), false), Lit.make(v(p2, h), false))))
                }
            }
        }
        return Problem(
            numBoolVars = pigeons * holes,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
    }

    @Test
    fun `tiered db proves unsat under an aggressive cap`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 5, holes = 4)).solve(
            BacktrackParams(
                randomSeed = 1L,
                variableSelector = Vsids(),
                lubyRestartBase = 50L, // restart often so the reduction policy runs repeatedly
                maxLearnedClauses = 40,
                tieredLearnedDb = true,
                midLbdThreshold = 6,
            ),
        )
        assertIs<SolveResult.Unsat>(verdict)
    }

    private fun clauseProblem(): Problem = Problem(
        numBoolVars = 5,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false), Lit.make(4, false))),
        ),
    )

    @Test
    fun `tiered db enumerates exactly the same models as the binary glue policy`() {
        fun models(params: BacktrackParams): Set<List<Boolean>> =
            BacktrackSolver(clauseProblem()).enumerate(params).map { it.bools.toList() }.toSet()

        val binary = models(
            BacktrackParams(randomSeed = 7L, variableSelector = Vsids(), lubyRestartBase = 8L, maxLearnedClauses = 4),
        )
        val tiered = models(
            BacktrackParams(
                randomSeed = 7L,
                variableSelector = Vsids(),
                lubyRestartBase = 8L,
                maxLearnedClauses = 4,
                tieredLearnedDb = true,
            ),
        )
        assertTrue(binary.isNotEmpty())
        assertEquals(binary, tiered, "the tiered reduction must not change the feasible set")
    }
}
