package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FinitePortfolioExecutorTest {
    @Test
    fun `finite source factories retain their selected plan through shared construction`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 2)), emptyArray())
        val prepared = source.prepareExecutionPlan(preferFinite = true)
        val problem = source.bake()
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.CSP, engine = EngineMix.BACKTRACK, seed = 17)
        val invocation = assertIs<PortfolioInvocation>(FinitePipeline.portfolioExecutor(
            problem, scenario, null, null, null, null, null, prepared, Cancellation.Never,
        ))

        val result = invocation.use { it.solve(Cancellation.Never) }

        val witness = assertIs<SolveResult.Sat>(result).assignment
        assertSame(prepared, invocation.preparedPlan)
        assertTrue(witness.intValue(0) in 1L..2L)
    }
}
