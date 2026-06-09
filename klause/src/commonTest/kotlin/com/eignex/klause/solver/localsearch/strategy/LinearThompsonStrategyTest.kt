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

/** The bandit move strategy only chooses among the candidate repair moves, so it must still
 *  drive LS to a valid solution — only move quality depends on what the bandit learns. */
class LinearThompsonStrategyTest {

    @Test
    fun `solves an exactly-one problem under the bandit move strategy`() {
        val n = 6
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })),
        )
        val solver = LocalSearchSolver(problem, strategy = LinearThompsonStrategy.thompson(seed = 1L))
        val r = solver.solve(LocalSearchParams(maxFlips = 50_000L, randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it }, "exactly-one violated by the witness")
    }
}
