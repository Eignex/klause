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
    fun `weighted optimization should account for the cost of replacing earlier relaxations`() {
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
        val softs = listOf(
            TotalizerOptimizer.WeightedSoft(Lit.make(0, true), weight = 2L),
            TotalizerOptimizer.WeightedSoft(Lit.make(1, true), weight = 2L),
            TotalizerOptimizer.WeightedSoft(Lit.make(2, true), weight = 3L),
        )

        val result = TotalizerOptimizer(problem.bake()).minimizeWeighted(softs, BacktrackParams())

        val optimal = assertIs<TotalizerOptimizer.Result.Optimal>(result)
        assertEquals(4L, optimal.lowerBound)
        assertEquals(4L, softs.sumOf { if (optimal.sample.boolValue(Lit.variable(it.lit))) 0L else it.weight })
    }

    @Test
    fun `weighted optimization should keep the heavier soft over the lighter under a mutex`() {
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
    fun `a globally unsatisfiable model should return infeasible`() {
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
