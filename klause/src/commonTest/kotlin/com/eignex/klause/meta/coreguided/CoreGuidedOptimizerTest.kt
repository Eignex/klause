package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreGuidedOptimizerTest {

    @Test
    fun `returned sample true cost equals reported lower bound under overlapping cores`() {
        // #80 regression. A "star" mutex: b0 conflicts with b1, b2, b3. All four softs
        // want their var true (weight 1). The single optimum is to drop b0 (cost 1),
        // satisfying every mutex at once. The relaxer machinery re-cores the shared soft
        // s0 across the three mutex cores, which is exactly the spent-soft state where the
        // recovered witness could be relaxed "for free" beyond the charged bound.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val softs = (0..3).map { CoreGuidedOptimizer.Soft(Lit.make(it, true)) }
        val r = CoreGuidedOptimizer(problem.bake()).minimize(softs, BacktrackParams())
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(r)
        assertEquals(1L, opt.lowerBound)
        val violated = softs.count { !opt.sample.bools[Lit.variable(it.lit)] }
        assertEquals(1, violated)
        assertEquals(false, opt.sample.bools[0])
    }

    @Test
    fun `weighted overlapping cores - sample cost matches bound`() {
        // Weighted star mutex with distinct weights forcing RC2 weight-splitting on the
        // shared soft. Optimum: keep the heavy b0 (w=6), violate b1+b2 (1+1) = cost 2.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        )
        val softs = listOf(
            CoreGuidedOptimizer.Soft(Lit.make(0, true), weight = 6L),
            CoreGuidedOptimizer.Soft(Lit.make(1, true), weight = 1L),
            CoreGuidedOptimizer.Soft(Lit.make(2, true), weight = 1L),
        )
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(
            CoreGuidedOptimizer(problem.bake()).minimize(softs, BacktrackParams()),
        )
        assertEquals(2L, opt.lowerBound)
        val cost = softs.sumOf { if (!opt.sample.bools[Lit.variable(it.lit)]) it.weight else 0L }
        assertEquals(opt.lowerBound, cost)
        assertEquals(true, opt.sample.bools[0])
    }

    @Test
    fun `globally unsat hard constraint returns Infeasible`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = CoreGuidedOptimizer(problem.bake()).minimize(
            listOf(CoreGuidedOptimizer.Soft(Lit.make(0, true))),
            BacktrackParams(),
        )
        assertIs<CoreGuidedOptimizer.Result.Infeasible>(r)
    }
}
