package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TotalizerOptimizerTest {

    @Test
    fun `weighted - heavier soft kept over lighter under mutex`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = TotalizerOptimizer(problem.bake()).minimizeWeighted(
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
        val r = TotalizerOptimizer(problem.bake()).minimize(
            listOf(TotalizerOptimizer.Soft(Lit.make(0, true))),
            BacktrackParams(),
        )
        assertIs<TotalizerOptimizer.Result.Infeasible>(r)
    }
}
