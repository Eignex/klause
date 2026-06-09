package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The bandit noise schedule only steers the cb/noise level (always a valid probability), so
 *  probSAT must still solve under it — only the schedule quality depends on what the bandit learns. */
class BanditNoiseControllerTest {

    @Test
    fun `bandit-adaptive probSAT solves an exactly-one problem`() {
        val n = 6
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })),
        )
        val solver = LocalSearchSolver(problem, strategy = ProbSat.bandit(seed = 1L))
        val sat = assertIs<SolveResult.Sat>(solver.solve(LocalSearchParams(maxFlips = 50_000L, randomSeed = 0L)))
        assertEquals(1, sat.assignment.bools.count { it }, "exactly-one violated")
    }
}
