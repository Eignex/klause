package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearOptimizationExecutionTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("public-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `public linear execution preserves exact wide integer and rational condition pins`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val wide = BigFraction.of(parseBigInt("18446744073709551616"), BIG_ONE)
        val third = BigFraction.ONE / BigFraction.ofLong(3)
        val pins = mapOf(integer to wide, real to third)
        val context = SourceProofContext(scope.copy(condition = SourceCondition(mapOf(0 to true), pins, pins)),
            setOf(bool, integer, real), emptyMap(), mapOf(integer to BigFraction.ONE, real to BigFraction.ONE))

        val result = OpenTheoryPipeline.optimizeLinear(context)

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals(wide + third, assertIs<SourceValue.Rational>(optimal.value).value)
        assertEquals(wide.num, optimal.sample.exactIntValue(0))
        assertEquals(third, assertNotNull(optimal.sample.exactReals).single())
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(context.scope, result.scope)
    }

    @Test
    fun `a conditional source optimum and bound cannot be reused in the parent scope`() {
        val parent = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val restricted = SourceProofContext(scope.copy(condition = SourceCondition(
            lower = mapOf(real to BigFraction.ONE))), parent.coordinates, parent.premises, parent.objective)
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        for (evidence in listOf(assertNotNull(result.terminal), assertNotNull(result.bound))) {
            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(parent.withLinearAuthority()).verify(evidence))
        }
    }

    @Test
    fun `an omitted affine objective cannot establish a zero optimum`() {
        val context = SourceProofContext(scope, setOf(real), emptyMap())

        val result = OpenTheoryPipeline.optimizeLinear(context.withLinearAuthority())

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
        assertNull(result.value)
    }

    @Test
    fun `sparse source coordinates decline before witness construction`() {
        val sparse = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val context = SourceProofContext(scope, setOf(sparse), emptyMap(), mapOf(sparse to BigFraction.ONE))

        val result = OpenTheoryPipeline.optimizeLinear(context)

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
    }

    @Test
    fun `public linear execution retains checkable limit evidence and accepted source values`() {
        val context = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(real to BigFraction.ONE))
        val accepted = ArrayList<BigFraction>()

        val result = OpenTheoryPipeline.optimizeLinear(context) { _, value -> accepted += value }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.terminal).claim)
        assertEquals(SourceEvidenceCheck.Checked, result.evidence.verify(claim, context.withLinearAuthority()))
        assertEquals(result.value, accepted.last())
        assertEquals(1L, result.stats.openTheory.openTheoryChecks)
    }

    @Test
    fun `callback failure retires the native invocation before another slice`() {
        val context = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            mapOf(real to BigFraction.ONE))
        val invocation = LinearAuthorityInvocation(context, TheoryParams())

        val failure = assertFailsWith<IllegalArgumentException> {
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ ->
                throw IllegalArgumentException("publication failed")
            }
        }

        assertEquals("publication failed", failure.message)
        assertFailsWith<IllegalStateException> {
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }
        invocation.close()
    }

    @Test
    fun `ordinary portfolio limits retain original direction and checkable approach evidence`() {
        for (maximize in listOf(false, true)) {
            val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
                intArrayOf(0), doubleArrayOf(1.0), if (maximize) LinearOp.LE else LinearOp.GE,
                if (maximize) 1.0 else 0.0, strict = true)), numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
            val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)), maximize)
            val context = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
            val captured = request.withLinearAuthority(context)
            val params = TheoryParams(maxLeaves = 32, openWorkLimit = 100_000, maxFlips = 12, alnsIterations = 1)

            for ((engine, lanes) in listOf(FiniteEngine.BACKTRACK to 1, FiniteEngine.FIXED to 1,
                FiniteEngine.MIXED to 1, FiniteEngine.MIXED to 2)) {
                val result = assertIs<OpenTheoryExecution.Optimize>(
                    OpenTheoryPipeline.execute(captured, params, engine, lanes)).result

                val authority = assertNotNull(assertIs<OpenTheoryOptimum.Bounded>(result).linearResult)
                val claim = assertIs<SourceClaim.Limit>(assertNotNull(authority.terminal).claim)
                assertEquals(SourceValue.Rational(if (maximize) BigFraction.ONE else BigFraction.ZERO), claim.value)
                assertEquals(context.scope, authority.scope)
                assertEquals(SourceEvidenceCheck.Checked,
                    authority.evidence.verify(claim, context.withLinearAuthority()))
                val value = assertNotNull(authority.value)
                assertTrue(if (maximize) value < BigFraction.ONE else value > BigFraction.ZERO)
            }
        }
    }

    @Test
    fun `ordinary portfolio rays retain moving integer coordinates and original source evidence`() {
        val model = Problem(numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0),
                Bits(1), Bits(1).also { it.set(0) }),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0),
                doubleArrayOf(10.0), LinearOp.GE, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(intCoefficients = longArrayOf(-1)))
        val context = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val captured = request.withLinearAuthority(context)
        val params = TheoryParams(maxLeaves = 32, openWorkLimit = 100_000, maxFlips = 12, alnsIterations = 1)

        for ((engine, lanes) in listOf(FiniteEngine.BACKTRACK to 1, FiniteEngine.FIXED to 1,
            FiniteEngine.MIXED to 1, FiniteEngine.MIXED to 2)) {
            val result = assertIs<OpenTheoryExecution.Optimize>(
                OpenTheoryPipeline.execute(captured, params, engine, lanes)).result

            val authority = assertNotNull(assertIs<OpenTheoryOptimum.Unbounded>(result).linearResult)
            val claim = assertIs<SourceClaim.Ray>(assertNotNull(authority.terminal).claim)
            val step = assertNotNull(claim.direction[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
            assertTrue(step > BigFraction.ZERO)
            assertEquals(BigFraction.ONE.den, step.den)
            assertNotNull(claim.direction[real])
            assertEquals(context.scope, authority.scope)
            assertEquals(SourceEvidenceCheck.Checked,
                authority.evidence.verify(claim, context.withLinearAuthority()))
        }
    }
}
