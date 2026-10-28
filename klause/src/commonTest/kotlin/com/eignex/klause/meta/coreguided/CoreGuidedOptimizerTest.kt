package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.CheckedLongOverflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CoreGuidedOptimizerTest {

    @Test
    fun `an optimal cost beyond the signed word range should fail explicitly`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, false))),
                Clause(intArrayOf(Lit.make(1, false))),
            ),
        )
        val softs = (0..1).map { CoreGuidedOptimizer.Soft(Lit.make(it, true), Long.MAX_VALUE) }

        assertFailsWith<CheckedLongOverflowException> {
            CoreGuidedOptimizer(problem.bake()).minimize(softs, BacktrackParams())
        }
    }

    @Test
    fun `overlapping cores should permit multiple relaxations within an earlier core`() {
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
        val softs = (0..2).map { CoreGuidedOptimizer.Soft(Lit.make(it, true)) }

        val result = CoreGuidedOptimizer(problem.bake()).minimize(softs, BacktrackParams())

        val optimal = assertIs<CoreGuidedOptimizer.Result.Optimal>(result)
        assertEquals(2L, optimal.lowerBound)
        assertEquals(2, softs.count { !optimal.sample.boolValue(Lit.variable(it.lit)) })
    }

    @Test
    fun `returned sample cost should equal the reported lower bound under overlapping cores`() {
        // Star mutex on b0: re-coring the shared soft leaves a witness that could over-relax past the bound.
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
    fun `weighted overlapping cores should yield a sample cost matching the bound`() {
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
    fun `a globally unsatisfiable hard constraint should return infeasible`() {
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
