package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityCapturedQuery
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceNumericalFeedbackTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("feedback", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE)),
    ), mapOf(x to BigFraction.ONE))

    @Test
    fun `acquisition should retain exact relaxation values and charge collection`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, source, cuts = false).feedback
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertEquals(BigFraction.ofLong(2).reciprocal(), available.query.point?.get(x))
        assertEquals(scope, available.scope)
        assertTrue(available.compatible(source, null))
        assertTrue(funding.allocated > 0)
        assertEquals(1L, feedback.collections)
    }

    @Test
    fun `acquisition should expose the recovered incumbent for independent credit`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)

        val acquired = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, source)
        }

        assertEquals(1L, assertNotNull(acquired.result.incumbent).intValue(0))
        assertIs<SourceNumericalFeedbackResult.Available>(acquired.feedback)
    }

    @Test
    fun `query feedback should reject incompatible source scopes after retraction`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val pinned = source.atScope(scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ONE))))
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, pinned, cuts = false).feedback
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertTrue(available.compatible(pinned, null))
        for (edited in listOf(source, source.atScope(scope.copy(revision = SourceRevision("other", "exact-affine"))),
            pinned.atScope(pinned.scope.copy(revision = SourceRevision("feedback", "other-semantics"))),
            pinned.atScope(pinned.scope.copy(objective = SourceObjective("other", ObjectiveSense.MINIMIZE))),
            SourceProofContext(pinned.scope, source.coordinates, mapOf("lower" to SourcePremise(
                SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))), source.objective))) {
            assertFalse(available.compatible(edited, null))
        }
    }

    @Test
    fun `a missing affine objective should differ from a constant objective`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val missing = SourceProofContext(scope, source.coordinates, source.premises)
        val constant = SourceProofContext(scope, source.coordinates, source.premises, emptyMap())
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, missing).feedback
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertTrue(available.compatible(missing, null))
        assertFalse(available.compatible(constant, null))
    }

    @Test
    fun `guard retraction should expose the newly queried relaxation instead of stale feedback`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))),
            setOf(x, bool), source.premises.mapValues { (_, premise) ->
                SourcePremise(premise.inequality, mapOf(0 to true))
            } + ("zero" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            source.objective)
        val retracted = guarded.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))))
        val records = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            listOf(feedback.acquire(authority, guarded, cuts = false).feedback,
                feedback.acquire(authority, retracted, cuts = false).feedback)
        }

        val active = assertIs<SourceNumericalFeedbackResult.Available>(records.first())
        val inactive = assertIs<SourceNumericalFeedbackResult.Available>(records.last())
        assertEquals(BigFraction.ofLong(2).reciprocal(), active.query.point?.get(x))
        assertEquals(BigFraction.ZERO, inactive.query.point?.get(x))
        assertFalse(active.compatible(retracted, null))
        assertTrue(inactive.compatible(retracted, null))
    }

    @Test
    fun `cutoff attribution should require an actual strict source objective premise`() {
        val cutoff = SourceNumericalCutoff(BigFraction.ONE, "improve")
        val conditioned = SourceProofContext(scope, source.coordinates, source.premises +
            ("improve" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE,
                strict = true))), source.objective)

        assertTrue(cutoff.enforcedBy(conditioned))
        assertFalse(cutoff.enforcedBy(source))
        assertFalse(cutoff.copy(value = BigFraction.ZERO).enforcedBy(conditioned))
    }

    @Test
    fun `cutoff construction should preserve objective direction and constant`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val cutoff = SourceNumericalCutoff(BigFraction.ofLong(5), "improve")
        for (sense in ObjectiveSense.entries) {
            val context = SourceProofContext(scope.copy(objective = SourceObjective("2x+3", sense)),
                source.coordinates, source.premises, mapOf(x to BigFraction.ofLong(2)), BigFraction.ofLong(3))

            val query = assertNotNull(cutoff.applyTo(context, funding))

            val expected = BigFraction.ofLong(if (sense == ObjectiveSense.MINIMIZE) 2L else -2L)
            val row = query.premises.getValue("improve").inequality
            assertEquals(mapOf(x to expected), row.coefficients)
            assertEquals(expected, row.rhs)
            assertTrue(row.strict)
            assertTrue(cutoff.enforcedBy(query))
        }
    }

    @Test
    fun `cutoff refutation support should remain local to the conditioned source`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val cutoff = SourceNumericalCutoff(BigFraction.ofLong(2).reciprocal(), "improve")
        val conditioned = assertNotNull(cutoff.applyTo(source, funding))
        val proof = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ofLong(2).reciprocal(), SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("improve")),
        )))
        val evidence = SourceEvidence(scope, SourceClaim.Refutation, proof)
        val query = LinearAuthorityCapturedQuery(0, conditioned, conditioned, listOf(x), emptyList(), emptySet(),
            null, emptyList(), emptyMap(), null, evidence, "checked-refutation")

        val projected = feedback.refutationSupport(SourceNumericalFeedbackResult.Available(query, cutoff))

        val support = assertIs<SourceNumericalSupportResult.Available>(projected)
        assertEquals(setOf(x), support.coordinates)
        assertEquals(setOf("lower", "improve"), support.premises)
        assertTrue(support.assumptions.isEmpty())
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(conditioned.withLinearAuthority()).verify(evidence))
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
    }

    @Test
    fun `unsupported refutation projection should decline instead of returning an empty support`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val query = LinearAuthorityCapturedQuery(0, source, source, listOf(x), emptyList(), emptySet(),
            null, emptyList(), emptyMap(), null, SourceEvidence(scope, SourceClaim.Refutation,
                SourceDerivation.SourceWitness), "checked-refutation")

        val projected = feedback.refutationSupport(SourceNumericalFeedbackResult.Available(query, null))

        assertEquals(SourceNumericalSupportResult.Reason.Unsupported,
            assertIs<SourceNumericalSupportResult.Unavailable>(projected).reason)
    }

    @Test
    fun `unavailable point should remain distinct from an available empty cut set`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val delayed = SourceProofContext(scope, setOf(x, bool), source.premises, source.objective)
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, delayed).feedback
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertEquals("open-booleans", available.query.state)
        assertNull(available.query.point)
        assertTrue(available.query.cuts.isEmpty())
    }

    @Test
    fun `cancelled collection should expose an unavailable outcome`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val stop = Cancellation { true }
        val feedback = SourceNumericalFeedback(funding, stop)
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, source).feedback
        }

        assertEquals(SourceNumericalFeedbackResult.Unavailable(source, null,
            SourceNumericalFeedbackResult.Reason.Cancelled),
            result)
    }

    @Test
    fun `an attached observer should not expand runtime feedback retention`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never, latest = false)
        val journal = LinearAuthorityCapture(1000, 1_000_000L, 16_000_000L, Cancellation.Never)
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            authority.capture = journal
            feedback.attach(authority)
            repeat(65) { journal.record(source, null, null, null, LinearAuthorityResult()) }
            feedback.consume(source)
        }

        assertEquals(64, journal.records.size)
        assertEquals(SourceNumericalFeedbackResult.Reason.Retention,
            assertIs<SourceNumericalFeedbackResult.Unavailable>(result).reason)
        assertTrue(funding.allocated > 0L)
    }

    @Test
    fun `latest delivery should expose a retracted scope after the archive fills`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val journal = LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))),
            setOf(x, bool), source.premises.mapValues { (_, premise) ->
                SourcePremise(premise.inequality, mapOf(0 to true))
            } + ("zero" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            source.objective)
        val retracted = guarded.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))))
        val later = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            authority.capture = journal
            feedback.acquire(authority, guarded, cuts = false)
            feedback.acquire(authority, retracted, cuts = false).feedback
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(later)
        assertEquals(1, journal.records.size)
        assertEquals(BigFraction.ZERO, available.query.point?.get(x))
        assertTrue(available.compatible(retracted, null))
        assertFalse(available.compatible(guarded, null))
        assertEquals(1L, feedback.lateCollections)
    }

    @Test
    fun `a query beyond the delivery allowance should be an oversized outcome`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val journal = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply { deliver(1L, 1L) }
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            authority.capture = journal
            feedback.acquire(authority, source, cuts = false).feedback
        }

        assertEquals(SourceNumericalFeedbackResult.Reason.Oversized,
            assertIs<SourceNumericalFeedbackResult.Unavailable>(result).reason)
    }

    @Test
    fun `detach should release delivery and keep the external archive charged`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val journal = LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            authority.capture = journal
            feedback.attach(authority)
            repeat(2) { journal.record(source, null, null, null, LinearAuthorityResult()) }
            val allocated = funding.allocated

            feedback.detach(authority)

            assertNull(journal.delivered)
            assertEquals(1, journal.records.size)
            assertTrue(authority.capture === journal)
            assertEquals(allocated + journal.constructedBytes, funding.allocated)
        }
    }

    @Test
    fun `feedback traversal should keep delivering queries after the archive fills`() {
        val records = arrayListOf<SourceNumericalFeedbackResult>()
        val result = LinearAuthorityInvocation(source, TheoryParams(sourceCuts = false), boundOrdered = true,
            supportBranching = true, onNumericalFeedback = records::add).use {
            it.captureTo(LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never))
            it.run { _, _ -> }
        }

        assertEquals(BigFraction.ONE, result.value)
        assertTrue(records.any { (it as? SourceNumericalFeedbackResult.Available)?.query?.refutation != null })
        assertTrue(assertNotNull(result.stats.smt.completeSearchStages)
            .getValue("numericalFeedbackLateCollections") > 0L)
    }

    @Test
    fun `prefix feedback traversal should report retention after the archive fills`() {
        val records = arrayListOf<SourceNumericalFeedbackResult>()
        val result = LinearAuthorityInvocation(source, TheoryParams(sourceCuts = false), boundOrdered = true,
            supportBranching = true, onNumericalFeedback = records::add, latestFeedback = false).use {
            it.captureTo(LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never))
            it.run { _, _ -> }
        }

        assertEquals(BigFraction.ONE, result.value)
        assertEquals(1, records.count { it is SourceNumericalFeedbackResult.Available })
        assertTrue(records.any {
            (it as? SourceNumericalFeedbackResult.Unavailable)?.reason == SourceNumericalFeedbackResult.Reason.Retention
        })
    }

    @Test
    fun `an archival observer should keep full records while later deliveries hold the requested projection`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never, request = LinearSupportBranching.REQUEST)
        val journal = LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))),
            setOf(x, bool), source.premises.mapValues { (_, premise) ->
                SourcePremise(premise.inequality, mapOf(0 to true))
            } + ("zero" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            source.objective)
        val retracted = guarded.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))))
        val delivered = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            authority.capture = journal
            listOf(feedback.acquire(authority, guarded, cuts = false).feedback,
                feedback.acquire(authority, retracted, cuts = false).feedback)
        }

        val (archived, late) = delivered.map { assertIs<SourceNumericalFeedbackResult.Available>(it).query }
        assertEquals(LinearAuthorityFeedbackRequest.Full, journal.records.single().projection)
        assertEquals(LinearAuthorityFeedbackRequest.Full, archived.projection)
        assertEquals(LinearSupportBranching.REQUEST, late.projection)
        assertEquals(BigFraction.ZERO, late.point?.get(x))
        assertTrue(late.cited.isEmpty())
        assertTrue(assertIs<SourceNumericalFeedbackResult.Available>(delivered.last()).compatible(retracted, null))
    }

    @Test
    fun `requested support feedback should reproduce full feedback decisions at lower cost`() {
        fun run(request: LinearAuthorityFeedbackRequest?) = LinearAuthorityInvocation(source,
            TheoryParams(sourceCuts = false),
            boundOrdered = true, supportBranching = true, feedbackRequest = request).use {
            it.captureTo(LinearAuthorityCapture(1, 1_000_000L, 16_000_000L, Cancellation.Never))
            it.run { _, _ -> }
        }

        val full = run(LinearAuthorityFeedbackRequest.Full)
        val requested = run(null)

        assertEquals(full.value, requested.value)
        assertEquals(full.terminal?.claim, requested.terminal?.claim)
        val expected = assertNotNull(full.stats.smt.completeSearchStages)
        val actual = assertNotNull(requested.stats.smt.completeSearchStages)
        for (decision in listOf("numericalSupportConsultations", "numericalSupportSelections",
            "numericalSupportFallbacks", "numericalFeedbackCollections", "numericalFeedbackLateCollections",
            "numericalFractionalSplits")) {
            assertEquals(expected[decision], actual[decision], decision)
        }
        assertTrue(actual.getValue("numericalFeedbackLateCollections") > 0L)
        assertTrue(actual.getValue("numericalFeedbackArithmeticTerms") <=
            expected.getValue("numericalFeedbackArithmeticTerms"))
        assertTrue(actual.getValue("numericalFundedAllocation") < expected.getValue("numericalFundedAllocation"))
    }

    @Test
    fun `feedback traversal should preserve complementary checked coverage`() {
        val records = arrayListOf<SourceNumericalFeedbackResult>()
        val result = LinearAuthorityInvocation(source, TheoryParams(sourceCuts = false), boundOrdered = true,
            supportBranching = true, onNumericalFeedback = records::add).use {
            it.run { _, _ -> }
        }

        assertEquals(BigFraction.ONE, result.value)
        assertNotNull(result.terminal)
        assertTrue(records.any { (it as? SourceNumericalFeedbackResult.Available)?.query?.refutation != null })
        assertTrue(assertNotNull(result.stats.smt.completeSearchStages).getValue("numericalSupportFallbacks") > 0L)
    }
}
