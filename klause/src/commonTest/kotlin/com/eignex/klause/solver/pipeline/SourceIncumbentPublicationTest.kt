package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceIncumbentPublicationTest {
    @Test
    fun `source receipts retain exact decimal coefficients and constants across engines`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.1))
        val base = assertNotNull(OpenTheoryRequest(model, objective).linearAuthorityContext(Cancellation.Never))
        val tenth = BigFraction.ofLong(10).reciprocal()
        val context = SourceProofContext(base.scope, base.coordinates, base.premises,
            base.objective.mapValues { tenth }, tenth * BigFraction.ofLong(3))
        val request = OpenTheoryRequest(model, objective).withLinearAuthority(context)
        val params = TheoryParams(openWorkLimit = 1000, maxFlips = 4, alnsIterations = 1)

        for (engine in FiniteEngine.entries) {
            val observed = mutableListOf<SourceIncumbentPublication>()
            val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(
                request, params, engine, 1, observed::add))

            val receipt = assertNotNull(result.acceptedIncumbent, "$engine: ${result.result}")
            assertSame(observed.last(), receipt)
            assertEquals(1L, receipt.version)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3).reciprocal()), receipt.value)
            assertEquals(BigFraction.ofLong(3).reciprocal(), receipt.sample.exactReals?.single())
        }
    }

    @Test
    fun `maximized heuristic receipts restore the original objective direction`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val observed = mutableListOf<SourceIncumbentPublication>()

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.searchWithoutTheory(model,
            TheoryParams(openWorkLimit = 1000, maxFlips = 12), objective, FiniteEngine.LOCAL_SEARCH, 1,
            maximize = true, onAccepted = observed::add))

        val receipt = assertNotNull(result.acceptedIncumbent)
        assertSame(observed.single(), receipt)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3).reciprocal()), receipt.value)
        assertIs<OpenTheoryOptimum.Bounded>(result.result)
    }

    @Test
    fun `a strict limit retains an accepted interior point rather than its boundary value`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(1.0))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val observed = mutableListOf<SourceIncumbentPublication>()

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request,
            TheoryParams(openWorkLimit = 1000), FiniteEngine.BACKTRACK, 1, observed::add))

        val receipt = assertNotNull(result.acceptedIncumbent)
        assertSame(observed.last(), receipt)
        val value = assertIs<SourceValue.Rational>(receipt.value).value
        assertTrue(value > BigFraction.ZERO && value <= BigFraction.ONE)
        assertEquals(receipt.sample.exactReals?.single(), value)
        assertIs<OpenTheoryOptimum.Bounded>(result.result)
    }

    @Test
    fun `successive maximized improvements retain their installation order`() {
        val model = Problem(1, 0, emptyArray(), arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)), maximize = true)
        val observed = mutableListOf<SourceIncumbentPublication>()

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request,
            TheoryParams(openWorkLimit = 1000, maxFlips = 0), FiniteEngine.BACKTRACK, 1, observed::add))

        assertEquals(listOf(1L, 2L), observed.map { it.version })
        val values = observed.map { assertIs<SourceValue.Rational>(it.value).value }
        assertTrue(values[0] < values[1])
        assertEquals(BigFraction.ofLong(2), values[1])
        assertSame(observed.last(), result.acceptedIncumbent)
    }

    @Test
    fun `publication cancellation propagates and a later invocation remains independent`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val params = TheoryParams(openWorkLimit = 1000, maxFlips = 0)
        val failure = CancellationException("publication failed")

        val thrown = assertFailsWith<CancellationException> {
            OpenTheoryPipeline.execute(request, params, FiniteEngine.MIXED, 2) { throw failure }
        }
        val later = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(
            request, params, FiniteEngine.MIXED, 2, null))

        assertSame(failure, thrown)
        assertEquals(1L, assertNotNull(later.acceptedIncumbent).version)
        assertIs<OpenTheoryOptimum.Optimal>(later.result)
    }
}
