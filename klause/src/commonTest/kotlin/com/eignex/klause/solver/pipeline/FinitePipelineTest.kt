package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FinitePipelineTest {

    @Test
    fun `selects the portfolio composition for a finite route`() {
        assertEquals(EngineMix.BACKTRACK, FinitePipeline.portfolioMix(FiniteEngine.BACKTRACK))
        assertEquals(EngineMix.LOCAL_SEARCH, FinitePipeline.portfolioMix(FiniteEngine.LOCAL_SEARCH))
        assertEquals(EngineMix.MIXED, FinitePipeline.portfolioMix(FiniteEngine.MIXED))
        assertEquals(EngineMix.ALNS, FinitePipeline.portfolioMix(FiniteEngine.ALNS))
    }

    @Test
    fun `keeps an unchanged finite model for the selected route`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray<IntDomain>(),
            factors = emptyArray<Factor>(),
        )

        val preparation = FinitePipeline.prepare(
            FinitePipelineRequest(
                problem = problem,
                engine = FiniteEngine.BACKTRACK,
                presolveConfig = PresolveConfig.NONE,
            ),
        )

        assertSame(problem, preparation.problem)
    }

    @Test
    fun `reconstructs an affine-eliminated variable`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(-2, 1), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )

        val preparation = FinitePipeline.prepare(
            FinitePipelineRequest(problem, FiniteEngine.BACKTRACK),
        )

        assertTrue(preparation.problem.factors.isEmpty())
        val reconstructed = preparation.reconstruct(Sample(BooleanArray(0), longArrayOf(2, 0)))
        assertEquals(5L, reconstructed.ints[1])
    }

    @Test
    fun `leaves a harvested model at the presolve fixpoint`() {
        val config = PresolveConfig.parse("default,+lp-harvest")
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1, -1, -1), intArrayOf(3, 0, 1, 2), LinearOp.GE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))

        val once = FinitePipeline.prepare(
            FinitePipelineRequest(problem, FiniteEngine.BACKTRACK, objective, config),
        )
        assertTrue(once.problem.requireFiniteIntDomains()[3].min >= 2)

        val again = FinitePipeline.prepare(
            FinitePipelineRequest(once.problem, FiniteEngine.BACKTRACK, objective, config),
        )
        assertSame(once.problem, again.problem)
    }
}
