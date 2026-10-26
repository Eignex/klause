package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityCaptureTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("capture", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE)),
    ), mapOf(x to BigFraction.ONE))

    @Test
    fun `candidate violation should precede insertion and survive owner close`() {
        val capture = LinearAuthorityCapture(2, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
            authority.advance(source, Cancellation.Never, cuts = true)
        }

        val before = capture.records.first()
        val after = capture.records.last()
        assertEquals(BigFraction.ofLong(2).reciprocal(), before.point?.get(x))
        assertEquals(BigFraction.ONE, after.point?.get(x))
        assertFalse(before.cuts.single().installed)
        assertTrue(before.cuts.single().checked)
        assertEquals(BigFraction.ofLong(2).reciprocal(), before.cuts.single().violation)
        assertTrue(after.cuts.single().installed)
        assertEquals(BigFraction.ZERO, after.cuts.single().violation)
    }

    @Test
    fun `explicit replay should reproduce a point and freshly checked bound under the same mask`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
        }
        val original = capture.records.single()

        val replay = LinearAuthorityReplay.run(original, emptySet(), 1_000_000L, 4_000_000L, Cancellation.Never)

        val query = assertNotNull(replay.query)
        assertEquals(original.point, query.point)
        assertEquals(original.inactive, query.inactive)
        assertEquals(original.bound?.claim, query.bound?.claim)
        assertEquals(1, replay.operations)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
            .verify(assertNotNull(query.bound)))
    }

    @Test
    fun `observer should preserve source operations and numerical accounting`() {
        val baseline = SourceLpBudget()
        val observed = SourceLpBudget()
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        val control = LinearAuthority(baseline, retainLp = true).use {
            it.advance(source, Cancellation.Never, cuts = true)
        }
        val treatment = LinearAuthority(observed, retainLp = true).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true)
        }

        assertEquals(control.bound?.claim, treatment.bound?.claim)
        assertEquals(control.split, treatment.split)
        assertEquals(baseline.operations, observed.operations)
        assertEquals(baseline.measuredPreparationWork, observed.measuredPreparationWork)
        assertEquals(baseline.measuredFloatWork, observed.measuredFloatWork)
        assertEquals(baseline.measuredBoundCertificationWork, observed.measuredBoundCertificationWork)
        assertEquals(baseline.measuredConflictCertificationWork, observed.measuredConflictCertificationWork)
    }

    @Test
    fun `guarded strict records should preserve activation across sibling retraction`() {
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val guarded = SourceProofContext(scope, setOf(x, y), mapOf(
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2)),
                BigFraction.ofLong(3), strict = true), emptyMap(), mapOf(y to BigFraction.ONE)),
        ), mapOf(x to BigFraction.MINUS_ONE))
        val enabled = guarded.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(y to BigFraction.ONE), upper = mapOf(y to BigFraction.ONE))))
        val template = assertNotNull(LinearAuthorityTemplate.prepare(guarded, Cancellation.Never, cuts = true))
        val capture = LinearAuthorityCapture(3, 10_000L, 1_000_000L, Cancellation.Never)
        for (current in listOf(enabled, guarded, enabled)) {
            val query = assertNotNull(LinearAuthorityQuery.prepare(current, template, Cancellation.Never,
                cutMask = emptySet()))
            capture.record(current, query, template, mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                LinearAuthorityResult())
        }

        assertEquals(listOf(true, false, true), capture.records.map { it.cuts.single().checked })
        assertTrue(capture.records.first().cited.first().first.strict)
        val cut = capture.records.first().cuts.single()
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(enabled).verify(SourceEvidence(
            enabled.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        assertEquals(listOf(false, false, false), capture.records.map { it.cuts.single().installed })
    }

    @Test
    fun `bounded capture should leave missing observations unknown`() {
        for (stop in listOf(Cancellation.Never, Cancellation { true })) {
            val capture = LinearAuthorityCapture(1, 0L, 0L, stop)

            capture.record(source, null, null, null, LinearAuthorityResult())

            assertTrue(capture.records.isEmpty())
            assertTrue(capture.limited)
            assertEquals(0L, capture.entries)
            assertEquals(0L, capture.bytes)
        }
    }

    @Test
    fun `open Boolean capture should not substitute an unknown pin`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, setOf(x, bool), mapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, bool to BigFraction.ONE),
                BigFraction.ONE)),
        ), mapOf(x to BigFraction.ONE))
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)

        capture.record(guarded, null, null, null, LinearAuthorityResult())

        assertEquals("open-booleans", capture.records.single().state)
        assertNull(capture.records.single().point)
        assertTrue(capture.records.single().cuts.isEmpty())
    }
}
