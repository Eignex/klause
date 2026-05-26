package com.eignex.klause.solver.optimize

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TotalizerOptimizerTest {

    @Test
    fun `no softs returns cost-0 optimum`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = TotalizerOptimizer(problem).minimize(emptyList(), BacktrackParams())
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(0L, opt.lowerBound)
    }

    @Test
    fun `single satisfiable soft yields cost 0`() {
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = TotalizerOptimizer(problem).minimize(
            listOf(TotalizerOptimizer.Soft(Lit.make(0, true))),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(0L, opt.lowerBound)
        assertEquals(true, opt.sample.bools[0])
    }

    @Test
    fun `two mutex softs cost 1`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = TotalizerOptimizer(problem).minimize(
            listOf(
                TotalizerOptimizer.Soft(Lit.make(0, true)),
                TotalizerOptimizer.Soft(Lit.make(1, true)),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(1L, opt.lowerBound)
        val trueCount = (if (opt.sample.bools[0]) 1 else 0) + (if (opt.sample.bools[1]) 1 else 0)
        assertEquals(1, trueCount)
    }

    @Test
    fun `three pairwise-mutex softs cost 2`() {
        // K3 mutex graph: any model has at most one of {b0, b1, b2} true.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val r = TotalizerOptimizer(problem).minimize(
            listOf(
                TotalizerOptimizer.Soft(Lit.make(0, true)),
                TotalizerOptimizer.Soft(Lit.make(1, true)),
                TotalizerOptimizer.Soft(Lit.make(2, true)),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(2L, opt.lowerBound)
        val trueCount = (if (opt.sample.bools[0]) 1 else 0) +
            (if (opt.sample.bools[1]) 1 else 0) +
            (if (opt.sample.bools[2]) 1 else 0)
        assertEquals(1, trueCount)
    }

    @Test
    fun `globally unsat returns Infeasible`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = TotalizerOptimizer(problem).minimize(
            listOf(TotalizerOptimizer.Soft(Lit.make(0, true))),
            BacktrackParams(),
        )
        assertIs<TotalizerOptimizer.Result.Infeasible>(r)
    }
}
