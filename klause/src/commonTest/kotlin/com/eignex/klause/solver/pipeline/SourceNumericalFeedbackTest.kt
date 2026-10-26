package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
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
            feedback.acquire(authority, source, cuts = false)
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertEquals(BigFraction.ofLong(2).reciprocal(), available.query.point?.get(x))
        assertEquals(scope, available.scope)
        assertTrue(available.compatible(source, null))
        assertTrue(funding.allocated > 0)
        assertEquals(1L, feedback.collections)
    }

    @Test
    fun `query feedback should reject incompatible source scopes after retraction`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val pinned = source.atScope(scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ONE))))
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, pinned, cuts = false)
        }

        val available = assertIs<SourceNumericalFeedbackResult.Available>(result)
        assertTrue(available.compatible(pinned, null))
        for (edited in listOf(source, source.atScope(scope.copy(revision = SourceRevision("other", "exact-affine"))),
            source.atScope(scope.copy(objective = SourceObjective("other", ObjectiveSense.MINIMIZE))))) {
            assertFalse(available.compatible(edited, null))
        }
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
    fun `unavailable point should remain distinct from an available empty cut set`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val feedback = SourceNumericalFeedback(funding, Cancellation.Never)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val delayed = SourceProofContext(scope, setOf(x, bool), source.premises, source.objective)
        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true).use { authority ->
            feedback.acquire(authority, delayed)
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
            feedback.acquire(authority, source)
        }

        assertEquals(SourceNumericalFeedbackResult.Unavailable(SourceNumericalFeedbackResult.Reason.Cancelled),
            result)
    }

    @Test
    fun `feedback traversal should preserve complementary checked coverage`() {
        val records = arrayListOf<SourceNumericalFeedbackResult>()
        val result = LinearAuthorityInvocation(source, TheoryParams(sourceCuts = false,
            sourceSupportBranching = true), boundOrdered = true, onNumericalFeedback = records::add).use {
            it.run { _, _ -> }
        }

        assertEquals(BigFraction.ONE, result.value)
        assertNotNull(result.terminal)
        assertTrue(records.any { (it as? SourceNumericalFeedbackResult.Available)?.query?.refutation != null })
        assertTrue(result.stats.smt.completeSearchStages.getValue("numericalSupportFallbacks") > 0L)
    }
}
