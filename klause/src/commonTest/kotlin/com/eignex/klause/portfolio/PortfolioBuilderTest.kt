package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortfolioBuilderTest {

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
    fun `a mixed portfolio over continuous variables builds no local-search arms`() {
        val scenario = PortfolioScenario(cores = 1, arms = 4, kind = Kind.COP, engine = EngineMix.MIXED)

        val workers = PortfolioBuilder.build(continuous, scenario)

        assertTrue(workers.isNotEmpty())
        assertTrue(workers.none { it.label.startsWith("ls/") }, workers.map { it.label }.toString())
    }

    @Test
    fun `leaving local-search arms out admits no arm the scenario did not compose`() {
        val scenario = PortfolioScenario(cores = 1, arms = 4, kind = Kind.COP, engine = EngineMix.MIXED)

        val workers = PortfolioBuilder.build(continuous, scenario)

        assertTrue(workers.none { it.label.startsWith("alns") }, workers.map { it.label }.toString())
    }

    @Test
    fun `a local-search portfolio keeps its arms when none can run the model`() {
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
