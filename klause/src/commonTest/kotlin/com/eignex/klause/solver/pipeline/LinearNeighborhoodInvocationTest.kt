package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearNeighborhoodInvocationTest {
    @Test
    fun `captured neighborhood pins retain exact receipts without proof claims`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(source, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val third = BigFraction.ofLong(3).reciprocal()
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val original = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val context = SourceProofContext(original.scope.copy(condition = SourceCondition(
            lower = mapOf(real to third), upper = mapOf(real to third))), original.coordinates,
            original.premises, original.objective, original.objectiveConstant, original.semanticsChecker)

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val execution = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(
                request.withLinearAuthority(context), TheoryParams(openWorkLimit = 1000, maxFlips = 4,
                    alnsIterations = 1), engine))

            val bounded = assertIs<OpenTheoryOptimum.Bounded>(execution.result)
            val authority = assertNotNull(bounded.linearResult)
            assertEquals(context.scope, authority.scope)
            assertNull(authority.bound)
            assertNull(authority.terminal)
            val receipt = assertNotNull(execution.acceptedIncumbent)
            assertEquals(SourceValue.Rational(third), receipt.value)
            assertEquals(third, assertNotNull(receipt.sample.exactReals).single())
            val certificate = assertNotNull(receipt.sample.witnessCertificate)
            val publication = assertIs<CompletedEnginePublication.Witness>(certificate.publication)
            assertEquals(context.scope, publication.invocation.scope)
            assertEquals(receipt.sample, publication.sample)
            assertEquals(third.toDouble(), publication.value)
        }
    }

    @Test
    fun `linear real strategies retain the same exact source witness`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(source)
        val params = TheoryParams(openWorkLimit = 1000, maxFlips = 20, randomSeed = 17, alnsIterations = 2)

        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
        for (engine in engines) {
            val result = assertIs<OpenTheoryExecution.Satisfy>(
                OpenTheoryPipeline.execute(request, params, engine),
            ).result

            assertEquals("1/3", assertIs<OpenTheoryResult.Sat>(result, "$engine: $result").assignment.realValue(0))
            if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
                assertTrue(result.stats.ls.moves.sum > 0, "$engine: ${result.stats}")
            }
        }
    }

    @Test
    fun `open integer repair searches coupled coordinates without changing source domains`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)))
        val request = OpenTheoryRequest(source)
        val params = TheoryParams(openWorkLimit = 1000, maxFlips = 30, randomSeed = 17)

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val result = assertIs<OpenTheoryExecution.Satisfy>(
                OpenTheoryPipeline.execute(request, params, engine),
            ).result

            val witness = assertIs<OpenTheoryResult.Sat>(result, "$engine: $result").assignment
            assertEquals(parseBigInt("4"), parseBigInt(witness.intValue(0)) + parseBigInt(witness.intValue(1)))
            assertTrue(result.stats.ls.completionsRefuted.sum > 0)
            assertTrue(!source.intBounds.hasLower(0) && !source.intBounds.hasUpper(0))
        }
    }

    @Test
    fun `wide integer objectives retain exact values across strategy families`() {
        val value = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value)))
        val request = OpenTheoryRequest(source, LinearObjective(intCoefficients = longArrayOf(1)))
        val params = TheoryParams(openWorkLimit = 1000, maxFlips = 4, alnsIterations = 1)

        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
        for (engine in engines) {
            val result = assertIs<OpenTheoryExecution.Optimize>(
                OpenTheoryPipeline.execute(request, params, engine),
            ).result

            when (engine) {
                FiniteEngine.BACKTRACK, FiniteEngine.MIXED ->
                    assertEquals(value.toString(), assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
                else -> {
                    val bounded = assertIs<OpenTheoryOptimum.Bounded>(result)
                    assertEquals(value.toString(), bounded.value.toString())
                    assertEquals(value.toString(), bounded.incumbent?.intValue(0))
                }
            }
        }
    }

    @Test
    fun `empty linear ALNS models execute an exact completion`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(OpenTheoryRequest(source),
            TheoryParams(maxFlips = 3), FiniteEngine.ALNS)).result

        assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(1.0, result.stats.ls.completions.sum)
    }

    @Test
    fun `exhausted heuristic allowances perform no completion`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val controls = listOf(
            TheoryParams(maxFlips = 0), TheoryParams(maxInstructions = 0), TheoryParams(openWorkLimit = 0),
        )

        for (params in controls) for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(OpenTheoryRequest(source),
                params, engine)).result

            assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
            assertEquals(0.0, result.stats.ls.completions.sum)
        }
    }
    @Test
    fun `mixed repairs retain wide integers beside fractional real coordinates`() {
        val value = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(source)

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request,
                TheoryParams(maxFlips = 20, openWorkLimit = 1000, randomSeed = 17), engine)).result

            val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals(value.toString(), witness.intValue(0))
            assertEquals("1/3", witness.realValue(0))
            assertTrue(result.stats.ls.moves.sum > 1)
        }
    }

}
