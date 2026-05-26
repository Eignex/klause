package com.eignex.klause.solver.optimize

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreGuidedOptimizerTest {

    @Test
    fun `no softs returns cost-0 optimum`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = CoreGuidedOptimizer(problem).minimize(emptyList(), BacktrackParams())
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(r)
        assertEquals(0L, opt.lowerBound)
        assertEquals(0, opt.coresFound)
    }

    @Test
    fun `single satisfiable soft yields cost 0`() {
        // No constraints; soft "b0 true" is trivially satisfiable.
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = CoreGuidedOptimizer(problem).minimize(
            listOf(CoreGuidedOptimizer.Soft(Lit.make(0, true), weight = 5L)),
            BacktrackParams(),
        )
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(r)
        assertEquals(0L, opt.lowerBound)
        assertEquals(true, opt.sample.bools[0])
    }

    @Test
    fun `unweighted MaxSAT picks the best of two mutually-exclusive softs`() {
        // Hard: ¬b0 ∨ ¬b1 (can't have both true).
        // Softs: want(b0=true, w=1), want(b1=true, w=1). Optimum: relax one, cost = 1.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val r = CoreGuidedOptimizer(problem).minimize(
            listOf(
                CoreGuidedOptimizer.Soft(Lit.make(0, true)),
                CoreGuidedOptimizer.Soft(Lit.make(1, true)),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(r)
        assertEquals(1L, opt.lowerBound)
        assertEquals(1, opt.coresFound)
        // Exactly one of b0/b1 should be true in the recovered sample.
        val trueCount = (if (opt.sample.bools[0]) 1 else 0) + (if (opt.sample.bools[1]) 1 else 0)
        assertEquals(1, trueCount)
    }

    @Test
    fun `weighted RC2 picks the heavier soft over the lighter one`() {
        // Hard: ¬b0 ∨ ¬b1.
        // Softs: want(b0=true, w=5), want(b1=true, w=3). Optimum: keep b0, drop b1; cost = 3.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val r = CoreGuidedOptimizer(problem).minimize(
            listOf(
                CoreGuidedOptimizer.Soft(Lit.make(0, true), weight = 5L),
                CoreGuidedOptimizer.Soft(Lit.make(1, true), weight = 3L),
            ),
            BacktrackParams(),
        )
        val opt = assertIs<CoreGuidedOptimizer.Result.Optimal>(r)
        assertEquals(3L, opt.lowerBound)
        // The cheaper-to-violate soft (b1) is the one that ends up false.
        assertEquals(true, opt.sample.bools[0])
        assertEquals(false, opt.sample.bools[1])
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
        val r = CoreGuidedOptimizer(problem).minimize(
            listOf(CoreGuidedOptimizer.Soft(Lit.make(0, true))),
            BacktrackParams(),
        )
        assertIs<CoreGuidedOptimizer.Result.Infeasible>(r)
    }

    @Test
    fun `RC2 with stratification still finds the same optimum as without`() {
        // Three softs with distinct weights; pair-mutex hard constraints push the
        // solver to keep exactly one. Optimum is to keep the weight-10 soft true and
        // pay 4 + 1 = 5 to violate the other two.
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
            CoreGuidedOptimizer.Soft(Lit.make(0, true), weight = 10L),
            CoreGuidedOptimizer.Soft(Lit.make(1, true), weight = 4L),
            CoreGuidedOptimizer.Soft(Lit.make(2, true), weight = 1L),
        )
        val withStrat = CoreGuidedOptimizer(problem).minimize(softs, BacktrackParams(), stratify = true)
        val withoutStrat = CoreGuidedOptimizer(problem).minimize(softs, BacktrackParams(), stratify = false)
        val a = assertIs<CoreGuidedOptimizer.Result.Optimal>(withStrat)
        val b = assertIs<CoreGuidedOptimizer.Result.Optimal>(withoutStrat)
        assertEquals(5L, a.lowerBound)
        assertEquals(5L, b.lowerBound)
        // Both runs must keep b0 true (the heaviest), violating b1 and b2.
        assertEquals(true, a.sample.bools[0])
        assertEquals(true, b.sample.bools[0])
    }
}
