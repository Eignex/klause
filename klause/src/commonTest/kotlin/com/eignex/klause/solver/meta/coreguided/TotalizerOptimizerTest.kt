package com.eignex.klause.solver.meta.coreguided

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
    fun `weighted - heavier soft kept over lighter under mutex`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = TotalizerOptimizer(problem).minimizeWeighted(
            listOf(
                TotalizerOptimizer.WeightedSoft(Lit.make(0, true), weight = 5L),
                TotalizerOptimizer.WeightedSoft(Lit.make(1, true), weight = 3L),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(3L, opt.lowerBound)
        assertEquals(true, opt.sample.bools[0])
        assertEquals(false, opt.sample.bools[1])
    }

    @Test
    fun `weighted - three-way pairwise mutex with distinct weights`() {
        // K3 mutex graph; weights 10/4/1. Optimum: keep the weight-10 soft, pay 5.
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
        val r = TotalizerOptimizer(problem).minimizeWeighted(
            listOf(
                TotalizerOptimizer.WeightedSoft(Lit.make(0, true), weight = 10L),
                TotalizerOptimizer.WeightedSoft(Lit.make(1, true), weight = 4L),
                TotalizerOptimizer.WeightedSoft(Lit.make(2, true), weight = 1L),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(5L, opt.lowerBound)
        assertEquals(true, opt.sample.bools[0])
    }

    @Test
    fun `weighted - large weights solve via lazily-built thresholds`() {
        // #91 regression. totalWeight = 1999, so the old eager encoding baked ~2000
        // ReifiedPseudoBoolean threshold factors up front; the lazy path materialises only
        // the handful the OLL loop assumes (here k=1 then k=1000). Optimum under the mutex:
        // keep the weight-1000 soft, drop the weight-999 one ⇒ cost 999.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = TotalizerOptimizer(problem).minimizeWeighted(
            listOf(
                TotalizerOptimizer.WeightedSoft(Lit.make(0, true), weight = 1000L),
                TotalizerOptimizer.WeightedSoft(Lit.make(1, true), weight = 999L),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<TotalizerOptimizer.Result.Optimal>(r)
        assertEquals(999L, opt.lowerBound)
        assertEquals(true, opt.sample.bools[0])
        assertEquals(false, opt.sample.bools[1])
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
