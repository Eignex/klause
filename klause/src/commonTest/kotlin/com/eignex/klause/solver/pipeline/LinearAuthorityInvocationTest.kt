package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
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
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityInvocationTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(
        SourceRevision("conditioned-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
    )

    @Test
    fun `opt in active rows should close a contradiction before any Boolean decision`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.MINUS_ONE)),
        ), mapOf(bool to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), activeRows = true).use {
            it.run { _, _ -> }
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceClaim.Refutation, terminal.claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(1L, stages["numericalPartialConflicts"])
        assertEquals(0L, result.stats.openTheory.openBoolDecisions)
        assertNull(stages["numericalChecks"])
        assertNull(result.incumbent)
    }

    @Test
    fun `scoped refutation reuse should skip numerical queries while proving every integer alternative`() {
        val left = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val right = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val half = BigFraction.ofLong(2).reciprocal()
        val sum = mapOf(left to BigFraction.ONE, right to BigFraction.ONE)
        val source = SourceProofContext(scope, setOf(integer, left, right, real), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(left to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(right to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum-upper" to SourcePremise(SourceInequality(sum, half)),
            "sum-lower" to SourcePremise(SourceInequality(sum.mapValues { it.value.negated() }, half.negated())),
            "positive" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ONE)),
            "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE))
        val params = TheoryParams(sourceCuts = false)

        for ((recordFeedback, resolution) in listOf(false to false, true to false, true to true)) {
            var baselineFeedback = 0
            var reusedFeedback = 0
            val baselineObserver: (SourceNumericalFeedbackResult) -> Unit = { baselineFeedback++ }
            val reusedObserver: (SourceNumericalFeedbackResult) -> Unit = { reusedFeedback++ }

            val baseline = LinearAuthorityInvocation(source, params, boundOrdered = true,
                onNumericalFeedback = baselineObserver.takeIf { recordFeedback }).use { it.run { _, _ -> } }
            val reused = LinearAuthorityInvocation(source, params.copy(sourceRefutationReuse = true,
                sourceRefutationPropagation = recordFeedback, sourceRefutationResolution = resolution),
                boundOrdered = true, onNumericalFeedback = reusedObserver.takeIf { recordFeedback }).use {
                if (!recordFeedback) it.run { _, _ -> } else {
                    var result: LinearInvocationResult? = null
                    repeat(64) { _ ->
                        if (result == null) result = it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
                    }
                    assertNotNull(result)
                }
            }

            assertEquals(SourceClaim.Refutation, assertNotNull(baseline.terminal).claim)
            val terminal = assertNotNull(reused.terminal)
            assertEquals(SourceClaim.Refutation, terminal.claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
            val stages = assertNotNull(reused.stats.smt.completeSearchStages)
            assertTrue((stages["numericalAvoidedQueries"] ?: 0L) +
                (stages["numericalRefutationUnitDeductions"] ?: 0L) > 0L)
            assertTrue(reused.stats.smt.sourceLp.operations < baseline.stats.smt.sourceLp.operations)
            assertEquals(0L, stages["numericalPendingLeaves"])
            if (recordFeedback) assertTrue(reusedFeedback < baselineFeedback)
        }
    }

    @Test
    fun `reused refutations should preserve a checked global optimum`() {
        val left = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val right = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val half = BigFraction.ofLong(2).reciprocal()
        val sum = mapOf(left to BigFraction.ONE, right to BigFraction.ONE)
        val source = SourceProofContext(scope, setOf(integer, left, right, real, bool), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(left to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(right to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum-upper" to SourcePremise(SourceInequality(sum, half), mapOf(0 to true)),
            "sum-lower" to SourcePremise(SourceInequality(sum.mapValues { it.value.negated() }, half.negated()),
                mapOf(0 to true)),
            "positive" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ONE)),
            "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            "feasible" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ofLong(-2)), mapOf(0 to false)),
        ), mapOf(real to BigFraction.ONE))
        val params = TheoryParams(sourceCuts = false)

        val baseline = LinearAuthorityInvocation(source, params).use { it.run { _, _ -> } }
        val reused = LinearAuthorityInvocation(source, params.copy(sourceRefutationPropagation = true)).use {
            it.run { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(baseline.terminal).claim)
        val terminal = assertNotNull(reused.terminal)
        val optimum = assertIs<SourceClaim.Optimal>(terminal.claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), optimum.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(reused.stats.smt.completeSearchStages)
        assertTrue(stages.getValue("numericalAvoidedQueries") > 0L)
        assertTrue(stages.getValue("learningSourceRefutationConsumed") > 0L)
        assertTrue(reused.stats.smt.sourceLp.operations < baseline.stats.smt.sourceLp.operations)
        assertEquals(0L, stages["numericalPendingLeaves"])
    }

    @Test
    fun `a unit prefix should avoid a numerical decision while preserving the checked optimum`() {
        val left = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val right = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val height = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(integer, left, right, real, height), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(left to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(right to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(left to BigFraction.MINUS_ONE,
                right to BigFraction.MINUS_ONE), BigFraction.ofLong(2).reciprocal().negated())),
            "left-height" to SourcePremise(SourceInequality(mapOf(left to BigFraction.ONE,
                height to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right-height" to SourcePremise(SourceInequality(mapOf(right to BigFraction.ONE,
                height to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "positive" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ONE)),
            "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE, height to BigFraction.ONE))
        val params = TheoryParams(sourceCuts = false, sourceRefutationReuse = true)

        val cached = LinearAuthorityInvocation(source, params).use { it.run { _, _ -> } }
        val propagated = LinearAuthorityInvocation(source, params.copy(sourceRefutationPropagation = true)).use {
            it.run { _, _ -> }
        }

        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
            assertIs<SourceClaim.Optimal>(assertNotNull(cached.terminal).claim).value)
        val terminal = assertNotNull(propagated.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(propagated.stats.smt.completeSearchStages)
        assertTrue(stages.getValue("numericalRefutationUnitDeductions") > 0L)
        assertTrue(stages.getValue("learningSourceRefutationConsumed") > 0L)
        assertTrue(propagated.stats.smt.sourceLp.operations < cached.stats.smt.sourceLp.operations)
        assertTrue(propagated.stats.openTheory.openTheoryDecisions < cached.stats.openTheory.openTheoryDecisions)
        assertEquals(0L, stages["numericalPendingLeaves"])
    }

    @Test
    fun `retained frontier should consume checked mixed source cuts`() {
        val source = SourceProofContext(scope, setOf(integer, real), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "integer" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(integer to BigFraction.ONE, real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use {
            it.run { _, _ -> }
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(1L, stages["numericalMaxActiveMixedCuts"])
        assertEquals(1L, stages["numericalCutMixedEligibleRows"])
    }

    @Test
    fun `retained frontier should consume source bound aggregates within the LP operation budget`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(integer, real, residual), mapOf(
            "row" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE, residual to BigFraction.ONE), BigFraction.ofLong(3))),
            "real-lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real-upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(2))),
            "residual-lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "residual-upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE),
                BigFraction.ofLong(100))),
        ), mapOf(integer to BigFraction.MINUS_ONE, real to BigFraction.ONE))

        for ((pointCuts, operations) in listOf(false to 1, true to 2)) {
            val result = LinearAuthorityInvocation(source, TheoryParams(sourceAggregateCuts = true,
                sourcePointCuts = pointCuts, sourceLpOperations = operations), boundOrdered = true).use {
                it.run { _, _ -> }
            }

            val terminal = assertNotNull(result.terminal)
            assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
                assertIs<SourceClaim.Optimal>(terminal.claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `frontier should consume a sparse residual rejected by small scalar admission`() {
        val reals = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val coefficients = reals.take(65).associateWith { checkNotNull(BigFraction.ofDouble(3.141592653589793)) }
        val value = checkNotNull(BigFraction.ofDouble(0.1))
        val source = SourceProofContext(scope, reals.toSet(), mapOf(
            "upper" to SourcePremise(SourceInequality(coefficients, value)),
            "lower" to SourcePremise(SourceInequality(coefficients.mapValues { it.value.negated() }, value.negated())),
        ), emptyMap())

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use {
            it.run { _, _ -> }
        }

        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(1L, stages["numericalPrimalRecoveryExactAdmissionInputBits"])
        assertEquals(1L, stages["numericalPrimalRecoverySparseCompleted"])
        assertNotNull(result.incumbent)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.terminal)))
    }

    @Test
    fun `borrowing an authority should preserve its exhausted cumulative funding`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val budget = SourceLpBudget(funding = SourceLpFunding(0, 1000))

        val result = LinearAuthority(budget, retainLp = true).use { authority ->
            LinearAuthorityInvocation(source, TheoryParams(), borrowedAuthority = authority).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
            }
        }

        assertEquals(TerminationReason.BudgetExhausted, assertNotNull(result).reason)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `checked source cuts should close a sparse integer contradiction at the root`() {
        val reals = (0 until 128).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, (listOf(integer) + reals).toSet(), mapOf(
            "upper" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(2)), BigFraction.ONE)),
            "lower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
        ), mapOf(integer to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use {
            it.run { _, _ -> }
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceClaim.Refutation, terminal.claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(1L, stages["numericalChecks"])
        assertEquals(1L, stages["numericalClosedLeaves"])
        assertEquals(2L, stages["numericalMaxActiveCuts"])
    }

    @Test
    fun `borrowed source cut work should be reported only by the traversal that spent it`() {
        val source = SourceProofContext(scope, setOf(integer), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
        ), mapOf(integer to BigFraction.ONE))

        LinearAuthority(SourceLpBudget(maxOperations = 1), retainLp = true).use { authority ->
            val first = LinearAuthorityInvocation(source, TheoryParams(), borrowedAuthority = authority,
                boundOrdered = true).use { it.run { _, _ -> } }
            val second = LinearAuthorityInvocation(source, TheoryParams(), borrowedAuthority = authority,
                boundOrdered = true).use { it.run { _, _ -> } }

            val initial = assertNotNull(first.stats.smt.completeSearchStages)
            val reused = assertNotNull(second.stats.smt.completeSearchStages)
            assertTrue(initial.getValue("numericalCutPreparationWork") > 0L)
            assertEquals(0L, reused["numericalCutPreparationWork"])
            assertEquals(0L, reused["numericalCutVerificationWork"])
        }
    }

    @Test
    fun `empty cumulative funding should keep an external witness without claiming completion`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(sourceLpWorkLimit = 0),
            external = { sample }).use { it.run { _, _ -> } }

        assertEquals(sample, result.incumbent)
        assertNull(result.terminal)
        assertEquals(TerminationReason.BudgetExhausted, result.reason)
    }

    @Test
    fun `unsliced numerical execution should resume sparse guidance pauses`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other), mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                other to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "x" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(real to BigFraction.ONE, other to BigFraction.ofLong(2)))
        val budget = SourceLpBudget(maxOperations = 1)

        val result = LinearAuthority(budget, retainLp = true,
            guidancePolicy = SparseSourceLpPolicy(quantum = 1L)).use { authority ->
            LinearAuthorityInvocation(source, TheoryParams(), borrowedAuthority = authority,
                boundOrdered = true).use { it.run { _, _ -> } }
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3)), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        assertTrue(assertNotNull(result.stats.smt.completeSearchStages).getValue("numericalLpPauses") > 0L)
        assertEquals(1, budget.operations)
    }

    @Test
    fun `frontier should close inherited bounds after an incumbent improves without new LP checks`() {
        val source = SourceProofContext(scope, setOf(bool, integer), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.ofLong(-21)), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to true)),
        ), mapOf(integer to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 2), boundOrdered = true,
            cuts = false).use {
            assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> })
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ONE), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(2L, stages["numericalChecks"])
        assertEquals(2L, stages["numericalInheritedBoundClosures"])
    }

    @Test
    fun `sparse frontier should close coordinate conflicts outside exact primal admission`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val reals = (0 until 127).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, (listOf(integer, other) + reals).toSet(), mapOf(
            "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
            "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
        ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true, cuts = false).use {
            assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> })
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(2L, stages["numericalCoordinateConflicts"])
        assertEquals(1L, stages["numericalLpPreparations"])
    }

    @Test
    fun `sparse frontier should combine checked LP refutations over complete integer siblings`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val reals = (0 until 127).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val coefficients = mapOf(integer to BigFraction.ofLong(2), other to BigFraction.ofLong(2))
        val source = SourceProofContext(scope, (listOf(integer, other) + reals).toSet(), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(coefficients, BigFraction.ONE)),
            "lower" to SourcePremise(SourceInequality(coefficients.mapValues { it.value.negated() },
                BigFraction.MINUS_ONE)),
        ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true, cuts = false).use {
            assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> })
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceClaim.Refutation, terminal.claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(3L, stages["numericalLpConflicts"])
        assertEquals(3L, stages["numericalClosedLeaves"])
    }

    @Test
    fun `sparse frontier should publish a recovered strict residual point`() {
        val reals = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, reals.toSet(), mapOf(
            "positive" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true)),
        ), emptyMap())

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use {
            assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> })
        }

        assertTrue(assertNotNull(assertNotNull(result.incumbent).exactReals).first() > BigFraction.ZERO)
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("numericalPrimalRecoverySourceChecked"))
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.terminal)))
    }

    @Test
    fun `bound frontier should preserve complete sibling proofs across slices`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        for ((sense, resolution) in ObjectiveSense.entries.flatMap { sense ->
            listOf(false, true).map { sense to it }
        }) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", sense)),
                setOf(integer, other), mapOf(
                    "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                    "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

            LinearAuthorityInvocation(source, TheoryParams(sourceRefutationPropagation = true,
                sourceRefutationResolution = resolution),
                boundOrdered = true).use { invocation ->
                var result: LinearInvocationResult? = null
                repeat(32) {
                    if (result == null) result = invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
                }

                val terminal = assertNotNull(assertNotNull(result).terminal)
                assertEquals(SourceValue.Rational(BigFraction.ofLong(2) * direction),
                    assertIs<SourceClaim.Optimal>(terminal.claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
            }
        }
    }

    @Test
    fun `bound frontier should withhold a global bound for an unchecked Boolean sibling`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ofLong(-10)), mapOf(0 to true)),
        ), mapOf(real to BigFraction.ONE))

        LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use { invocation ->
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
            val snapshot = invocation.snapshot(TerminationReason.BudgetExhausted)

            assertNotNull(snapshot.incumbent)
            assertNull(snapshot.bound)
            assertNull(snapshot.terminal)
        }
    }

    @Test
    fun `directional history should preserve exhaustive fractional sibling proofs`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        for (sense in ObjectiveSense.entries) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", sense)),
                setOf(integer, other), mapOf(
                    "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                    "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

            val result = assertNotNull(LinearAuthorityInvocation(source, TheoryParams(), useBranchHistory = true).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
            })

            val terminal = assertNotNull(result.terminal)
            val claim = assertIs<SourceClaim.Optimal>(terminal.claim)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2) * direction), claim.value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `a checked incumbent should close a dominated fractional sibling`() {
        for (sense in ObjectiveSense.entries) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(
                scope.copy(objective = SourceObjective("x", sense)),
                setOf(bool, integer),
                mapOf(
                    "false" to SourcePremise(
                        SourceInequality(mapOf(integer to direction.negated()), BigFraction.MINUS_ONE),
                        mapOf(0 to false),
                    ),
                    "true" to SourcePremise(
                        SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction), BigFraction.ofLong(-3)),
                        mapOf(0 to true),
                    ),
                ),
                mapOf(integer to BigFraction.ONE),
            )

            val result = assertNotNull(LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 2)).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
            })

            val terminal = assertNotNull(result.terminal)
            val claim = assertIs<SourceClaim.Optimal>(terminal.claim)
            assertEquals(SourceValue.Rational(direction), claim.value)
            assertEquals(TerminationReason.SearchExhausted, result.reason)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `integer strict positivity should reach an attained integer minimum`() {
        val source = SourceProofContext(
            scope,
            setOf(integer),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(integer to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
    }

    @Test
    fun `a source optimum should require both guarded Boolean branches`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `incomplete branch coverage should keep the incumbent without a global optimum`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 1)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertNull(result?.terminal)
        assertNotNull(result?.incumbent)
        assertNull(result.bound)
    }

    @Test
    fun `an infeasible guarded branch should contribute its checked refutation to global coverage`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to false)),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `an attained sibling at the global limit should establish attainment`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                        strict = true,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `strict branch coverage and one approach should establish a global unattained limit`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                        strict = true,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `a cancelled invocation should preserve an accepted external incumbent`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        var stop = false
        val callbacks = ArrayList<Sample>()
        val invocation = LinearAuthorityInvocation(source, TheoryParams(), external = { sample })

        val result = invocation.use {
            it.runSlice(Cancellation { stop }, Long.MAX_VALUE, -1) { accepted, _ ->
                callbacks += accepted
                stop = true
            }
        }

        assertNull(result?.terminal)
        assertEquals(sample, result?.incumbent)
        assertEquals(listOf(sample), callbacks)
    }

    @Test
    fun `cancellation after acceptance should keep a checked global bound`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )
        var stop = false
        val invocation = LinearAuthorityInvocation(source, TheoryParams())

        val result = invocation.use {
            it.runSlice(Cancellation { stop }, Long.MAX_VALUE, -1) { _, _ -> stop = true }
        }

        assertNull(result?.terminal)
        assertEquals(TerminationReason.Cancelled, result?.reason)
        assertNotNull(result?.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), bound.value)
    }

    @Test
    fun `closing a paused traversal should keep its checked root bound and incumbent`() {
        val source = SourceProofContext(
            scope,
            setOf(integer),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(integer to BigFraction.ONE),
        )
        val invocation = LinearAuthorityInvocation(source, TheoryParams())

        val paused = invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
        invocation.close()
        val snapshot = invocation.snapshot(TerminationReason.Cancelled)

        assertNull(paused)
        assertNull(snapshot.terminal)
        assertNotNull(snapshot.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(snapshot.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), bound.value)
        assertEquals(TerminationReason.Cancelled, snapshot.reason)
    }
}
