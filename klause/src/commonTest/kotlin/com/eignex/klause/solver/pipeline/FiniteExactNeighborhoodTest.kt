package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FiniteExactNeighborhoodTest {
    @Test
    fun `pinned mixed products retain exact source checks during real repairs`() {
        val source = Problem(0, 1, arrayOf(IntDomain(3, 3)),
            arrayOf(RealProduct(0, 0, 1, 0.0, 1.0), Linear(intArrayOf(), doubleArrayOf(),
                intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 3.0)).bake()
        val point = assertNotNull(source.fixedDiscreteSample())

        for (adaptive in listOf(false, true)) {
            val worker = finiteExactNeighborhoodWorker(source, point, null, null, adaptive, 17, 0)
            val result = worker.use { it.solve(Cancellation.Never) }

            val witness = assertIs<SolveResult.Sat>(result).assignment
            assertEquals("1/3", witness.exactReals?.get(0).toString())
            assertEquals("1", witness.exactReals?.get(1).toString())
            assertTrue(result.stats.ls.completionsRefuted.sum > 0)
            assertNull(witness.witnessCertificate)
        }
    }
    @Test
    fun `bound real repairs publish their actual containing invocation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(3, 3)),
            arrayOf(RealProduct(0, 0, 1, 0.0, 1.0), Linear(intArrayOf(), doubleArrayOf(),
                intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 3.0)).bake()
        val point = assertNotNull(source.fixedDiscreteSample())
        val binding = EngineInvocation(source.prepareExecutionPlan(preferFinite = true), ModelIdentity.of(source))
        val worker = finiteExactNeighborhoodWorker(source, point, null, null, false, 17, 0).bindInvocation(binding)

        val result = worker.use { it.solve(Cancellation.Never) }

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val publication = assertIs<CompletedEnginePublication.Witness>(sample.witnessCertificate?.publication)
        assertSame(binding, publication.invocation)
        assertEquals(sample, publication.sample)
        assertEquals("1/3", publication.sample.exactReals?.get(0).toString())
    }

    @Test
    fun `finite mixed portfolios retain fractional cost improvements across integer branches`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(-1), intArrayOf(0), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(constant = 1L shl 62, realCoefficients = doubleArrayOf(-1.0))
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.COP,
            engine = EngineMix.BACKTRACK, seed = 17, lpCeiling = LpConfig.OFF)
        val executor = FinitePipeline.portfolioExecutor(source, scenario, objective, null, null, null)

        val result = executor.use { it.minimize() }

        val sample = assertIs<MinimizeResult.Optimal>(result).sample
        assertEquals(2L, sample.intValue(0))
        assertEquals("2/3", sample.exactReals?.single().toString())
    }

}
