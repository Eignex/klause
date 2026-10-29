package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearOptimizationExecutionTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(
        SourceRevision("public-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
    )

    @Test
    fun `a standalone linear result should include numerical owner retirement`() {
        val context = SourceProofContext(scope, setOf(real), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE))

        val result = OpenTheoryPipeline.optimizeLinear(context)

        assertTrue(result.stats.smt.sourceLp.operations > 0L)
        assertEquals(1L, assertNotNull(result.stats.smt.completeSearchStages)[
            "numericalCutLifecycleRECIPE_RETIREMENT_OwnerClosed"])
    }

    @Test
    fun `cancelling a scoped portfolio should keep its checked incumbent and conditional root bound`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val original = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val third = BigFraction.of(BIG_ONE, parseBigInt("3"))
        val context = SourceProofContext(
            original.scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to third),
                ),
            ),
            original.coordinates,
            original.premises,
            original.objective,
            original.objectiveConstant,
            original.semanticsChecker,
        )

        for (lanes in listOf(1, 4)) {
            var cancelled = false
            var observed: SourceIncumbentPublication? = null
            val params = TheoryParams(
                openWorkLimit = 100_000,
                maxFlips = 0,
                cancellation = Cancellation { cancelled },
            )

            val execution = assertIs<OpenTheoryExecution.Optimize>(
                OpenTheoryPipeline.execute(
                    request.withLinearAuthority(context),
                    params,
                    FiniteEngine.MIXED,
                    lanes,
                ) {
                    observed = it
                    cancelled = true
                },
            )

            val bounded = assertIs<OpenTheoryOptimum.Bounded>(execution.result)
            val authority = assertNotNull(bounded.linearResult)
            assertEquals(TerminationReason.Cancelled, bounded.reason)
            assertEquals(context.scope, authority.scope)
            assertNull(authority.terminal)
            val bound = assertIs<SourceClaim.Bound>(assertNotNull(authority.bound).claim)
            assertEquals(SourceValue.Rational(third), bound.value)
            assertEquals(
                SourceEvidenceCheck.Checked,
                authority.evidence.verify(bound, context.withLinearAuthority()),
            )
            assertSame(assertNotNull(observed), execution.acceptedIncumbent)
            assertSame(observed.sample, authority.incumbent)
            assertEquals(third, authority.value)
        }
    }

    @Test
    fun `ordinary complete routes should preserve captured exact coordinate pins and source receipts`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                Bits(1),
                Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(
            model,
            LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(1.0)),
        )
        val original = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val wide = BigFraction.of(parseBigInt("18446744073709551616"), BIG_ONE)
        val third = BigFraction.of(BIG_ONE, parseBigInt("3"))
        val pins = mapOf(integer to wide, real to third)
        val context = SourceProofContext(
            original.scope.copy(condition = SourceCondition(mapOf(0 to true), pins, pins)),
            original.coordinates,
            original.premises,
            original.objective,
            original.objectiveConstant,
            original.semanticsChecker,
        )
        val params = TheoryParams(maxLeaves = 32, openWorkLimit = 100_000, maxFlips = 0)

        for ((engine, lanes) in listOf(FiniteEngine.BACKTRACK to 1, FiniteEngine.MIXED to 4)) {
            val publications = ArrayList<SourceIncumbentPublication>()

            val execution = assertIs<OpenTheoryExecution.Optimize>(
                OpenTheoryPipeline.execute(
                    request.withLinearAuthority(context),
                    params,
                    engine,
                    lanes,
                ) { publications += it },
            )

            val authority = assertNotNull(assertIs<OpenTheoryOptimum.Optimal>(execution.result).linearResult)
            val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(authority.terminal).claim)
            assertEquals(SourceValue.Rational(wide + third), optimal.value)
            assertEquals(
                SourceEvidenceCheck.Checked,
                authority.evidence.verify(optimal, context.withLinearAuthority()),
            )
            val receipt = assertNotNull(execution.acceptedIncumbent)
            assertSame(publications.last(), receipt)
            assertSame(receipt.sample, authority.incumbent)
            assertTrue(receipt.sample.boolValue(0))
            assertEquals(wide.num, receipt.sample.exactIntValue(0))
            assertEquals(third, assertNotNull(receipt.sample.exactReals).single())
        }
    }

    @Test
    fun `ordinary witness only routes should keep exact scoped receipts without terminal claims`() {
        val third = BigFraction.of(BIG_ONE, parseBigInt("3"))
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                ExactSearchRow.of(
                    intArrayOf(Term.ofRealVar(0)),
                    listOf(BigFraction.ONE),
                    LinearOp.EQ,
                    third,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val original = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val context = SourceProofContext(
            original.scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to third),
                    upper = mapOf(real to third),
                ),
            ),
            original.coordinates,
            original.premises,
            original.objective,
            original.objectiveConstant,
            original.semanticsChecker,
        )
        val params = TheoryParams(openWorkLimit = 100_000, maxFlips = 12, alnsIterations = 1)

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val publications = ArrayList<SourceIncumbentPublication>()

            val execution = assertIs<OpenTheoryExecution.Optimize>(
                OpenTheoryPipeline.execute(
                    request.withLinearAuthority(context),
                    params,
                    engine,
                    1,
                ) { publications += it },
            )

            val bounded = assertIs<OpenTheoryOptimum.Bounded>(execution.result)
            assertNull(bounded.linearResult?.terminal)
            assertNull(bounded.linearResult?.bound)
            val receipt = assertNotNull(execution.acceptedIncumbent)
            assertSame(publications.last(), receipt)
            assertEquals(SourceValue.Rational(third), receipt.value)
            assertEquals(third, assertNotNull(receipt.sample.exactReals).single())
        }
    }

    @Test
    fun `public linear execution should preserve exact wide integer and rational condition pins`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val wide = BigFraction.of(parseBigInt("18446744073709551616"), BIG_ONE)
        val third = BigFraction.of(BIG_ONE, parseBigInt("3"))
        val pins = mapOf(integer to wide, real to third)
        val context = SourceProofContext(
            scope.copy(condition = SourceCondition(mapOf(0 to true), pins, pins)),
            setOf(bool, integer, real),
            emptyMap(),
            mapOf(integer to BigFraction.ONE, real to BigFraction.ONE),
        )

        val result = OpenTheoryPipeline.optimizeLinear(context)

        val optimal = assertIs<SourceClaim.Optimal>(assertNotNull(result.terminal).claim)
        assertEquals(wide + third, assertIs<SourceValue.Rational>(optimal.value).value)
        assertEquals(wide.num, optimal.sample.exactIntValue(0))
        assertEquals(third, assertNotNull(optimal.sample.exactReals).single())
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(context.scope, result.scope)
    }

    @Test
    fun `a conditional source optimum and bound should not be reused in the parent scope`() {
        val parent = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val restricted = SourceProofContext(
            scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to BigFraction.ONE),
                ),
            ),
            parent.coordinates,
            parent.premises,
            parent.objective,
        )
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        for (evidence in listOf(assertNotNull(result.terminal), assertNotNull(result.bound))) {
            assertIs<SourceEvidenceCheck.Rejected>(
                SourceEvidenceVerifier(parent.withLinearAuthority()).verify(evidence),
            )
        }
    }

    @Test
    fun `an omitted affine objective should not establish a zero optimum`() {
        val context = SourceProofContext(scope, setOf(real), emptyMap())

        val result = OpenTheoryPipeline.optimizeLinear(context.withLinearAuthority())

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
        assertNull(result.value)
    }

    @Test
    fun `sparse source coordinates should decline before witness construction`() {
        val sparse = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val context = SourceProofContext(scope, setOf(sparse), emptyMap(), mapOf(sparse to BigFraction.ONE))

        val result = OpenTheoryPipeline.optimizeLinear(context)

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
    }

    @Test
    fun `public linear execution should keep checkable limit evidence and accepted source values`() {
        val context = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )
        val accepted = ArrayList<BigFraction>()

        val result = OpenTheoryPipeline.optimizeLinear(context) { _, value -> accepted += value }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.terminal).claim)
        assertEquals(SourceEvidenceCheck.Checked, result.evidence.verify(claim, context.withLinearAuthority()))
        assertEquals(result.value, accepted.last())
        assertEquals(1L, result.stats.openTheory.openTheoryChecks)
    }

    @Test
    fun `callback failure should retire the native invocation before another slice`() {
        val context = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )
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
}
