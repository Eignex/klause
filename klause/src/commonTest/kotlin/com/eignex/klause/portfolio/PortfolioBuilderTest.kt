package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortfolioBuilderTest {

    @Test
    fun `only added variants wait for an incumbent in an expanded curated pool`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, arms = 12)

        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()
        val workers = PortfolioBuilder.build(problem, scenario)

        try {
            assertEquals(
                listOf(
                    "ls/cbls-chain/ils-basin",
                    "ls/cbls-chain-noinv/fixed",
                    "ls/cbls-notabu/fixed",
                    "ls/cbls-lonoise/fixed",
                ),
                workers.filter { it.improvementOnly && it.family == ArmFamily.LocalSearch }.map { it.label },
            )
            assertTrue(workers.filter { !it.improvementOnly }.any { it.label == "bt/satOptimized" })
        } finally {
            workers.forEach { it.close() }
        }
    }

    private val continuous = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 2L),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(3.0),
    ).bake()

    @Test
    fun `the default continuous optimization pool appends node LP after its incumbent workers`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)

        val workers = PortfolioBuilder.build(continuous, scenario,
            objective = LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        try {
            assertEquals(
                listOf("bt/satOptimized", "bt/conflictDriven", "bt/lp-default"),
                workers.filter { it.label.startsWith("bt/") }.map { it.label },
            )
            assertEquals(4, workers.count { it.family == ArmFamily.LocalSearch })
            assertEquals("bt/lp-default", workers.last().label)
            assertFalse(workers.last().improvementOnly)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `an auxiliary LP worker waits for a finite objective incumbent`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val workers = PortfolioBuilder.build(continuous, scenario,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)))

        try {
            assertEquals(listOf("bt/lp-default"), workers.filter { it.improvementOnly }.map { it.label })
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a disabled bounding ceiling keeps the continuous incumbent pool`() {
        val ceilings = listOf(LpConfig.OFF, LpConfig(overrides = mapOf(LpTechnique.BOUNDING to false)))

        for (ceiling in ceilings) {
            val scenario = PortfolioScenario.sequential(Kind.COP).copy(lpCeiling = ceiling)
            val workers = PortfolioBuilder.build(continuous, scenario)

            try {
                assertEquals(
                    listOf("bt/satOptimized", "bt/conflictDriven"),
                    workers.filter { it.label.startsWith("bt/") }.map { it.label },
                )
                assertEquals(4, workers.count { it.family == ArmFamily.LocalSearch })
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a mixed portfolio over continuous variables builds local-search arms`() {
        val scenario = PortfolioScenario(cores = 1, arms = 4, kind = Kind.COP, engine = EngineMix.MIXED)

        val workers = PortfolioBuilder.build(continuous, scenario)

        assertTrue(workers.any { it.label.startsWith("ls/") }, workers.map { it.label }.toString())
    }

    @Test
    fun `a local-search portfolio over continuous columns builds every arm`() {
        val scenario = PortfolioScenario(cores = 1, arms = 2, kind = Kind.COP, engine = EngineMix.LOCAL_SEARCH)

        val workers = PortfolioBuilder.build(continuous, scenario)

        assertEquals(2, workers.size)
    }

    @Test
    fun `a local-search arm over continuous columns reports certified real values`() {
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.CSP, engine = EngineMix.LOCAL_SEARCH)
        val worker = PortfolioBuilder.build(continuous, scenario).single()

        val result = worker.solve(Cancellation.Never, maxInstructions = 10_000)

        assertNotNull(assertIs<SolveResult.Sat>(result).assignment.exactReals)
    }
}
