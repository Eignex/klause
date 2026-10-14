package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FinitePortfolioExecutorTest {
    @Test
    fun `bounded Boolean proof service preserves one feasibility share for complete siblings`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        for ((cores, arms) in listOf(
            1 to PortfolioScenario.DEFAULT_ARMS,
            1 to 12,
            2 to PortfolioScenario.DEFAULT_ARMS,
        )) {
            val workers = PortfolioBuilder.build(problem,
                PortfolioScenario.sequential(Kind.COP).copy(cores = cores, arms = arms), objective)

            try {
                val shares = booleanIncumbentShares(workers)

                if (cores == 1) {
                    val families = workers.map { it.family }.distinct().size
                    assertEquals(1.0 / families, shares.sum())
                    for ((index, worker) in workers.withIndex()) {
                        assertEquals(
                            worker.family == ArmFamily.Backtrack && !worker.auxiliaryProof && !worker.improvementOnly,
                            shares[index] > 0.0,
                        )
                    }
                    if (arms == 12) assertTrue(workers.any {
                        it.family == ArmFamily.Backtrack && !it.auxiliaryProof && it.improvementOnly
                    })
                } else assertTrue(shares.isEmpty())
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `conditional root floors cannot prove the unconditioned source optimum`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val prepared = source.prepareExecutionPlan(objective, preferFinite = true)
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.COP, engine = EngineMix.BACKTRACK,
            btEdit = { it.copy(assumptions = Assumptions.None.withInt(0, 1L)) })
        val invocation = FinitePipeline.portfolioExecutor(source.bake(), scenario, objective, null, null, null,
            null, prepared, Cancellation.Never)

        val result = invocation.use { it.minimize(Cancellation.Never, null) }

        assertFalse(result is MinimizeResult.Optimal)
    }

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
