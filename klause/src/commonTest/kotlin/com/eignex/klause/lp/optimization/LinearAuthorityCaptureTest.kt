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
import com.eignex.klause.solver.pipeline.LinearAuthorityInvocation
import com.eignex.klause.solver.pipeline.TheoryParams
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
    private val installed = LinearAuthorityFeedbackRequest(point = true,
        cuts = LinearAuthorityFeedbackRequest.Cuts.Installed)

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
    fun `retracting a cut mask should remove its stronger retained coordinate side`() {
        val capture = LinearAuthorityCapture(2, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true)
            authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
        }

        val retracted = capture.records.last()
        assertEquals(BigFraction.ofLong(2).reciprocal(), retracted.point?.get(x))
        assertFalse(retracted.cuts.single().installed)
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
    fun `explicit replay should preserve strict premises under established Boolean guards`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))),
            setOf(x, bool), mapOf("strict" to SourcePremise(SourceInequality(
                mapOf(x to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE, strict = true), mapOf(0 to true))),
            mapOf(x to BigFraction.ONE))
        val template = assertNotNull(LinearAuthorityTemplate.prepare(guarded, Cancellation.Never, cuts = true))
        val query = assertNotNull(LinearAuthorityQuery.prepare(guarded, template, Cancellation.Never,
            cutMask = emptySet()))
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        capture.record(guarded, query, template, mapOf(x to BigFraction.ofLong(2).reciprocal()),
            LinearAuthorityResult())

        val replay = LinearAuthorityReplay.run(capture.records.single(), emptySet(), 1_000_000L, 4_000_000L,
            Cancellation.Never)

        val record = assertNotNull(replay.query)
        assertEquals(guarded.scope, record.context.scope)
        assertTrue(record.cited.first().first.strict)
        assertEquals(BigFraction.ofLong(2).reciprocal(), record.cuts.single().violation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(guarded.withLinearAuthority())
            .verify(assertNotNull(record.bound)))
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
    fun `observer should preserve exhaustive frontier traversal and sibling proof accounting`() {
        val capture = LinearAuthorityCapture(16, 100_000L, 4_000_000L, Cancellation.Never)
        val params = TheoryParams(sourceCuts = false)
        val baseline = LinearAuthorityInvocation(source, params, boundOrdered = true).use {
            it.run { _, _ -> }
        }
        val treatment = LinearAuthorityInvocation(source, params, boundOrdered = true).use {
            it.captureTo(capture)
            it.run { _, _ -> }
        }

        assertEquals(baseline.value, treatment.value)
        assertEquals(baseline.terminal?.claim, treatment.terminal?.claim)
        assertEquals(baseline.stats.smt.completeSearchStages.filterKeys { !it.endsWith("Nanos") },
            treatment.stats.smt.completeSearchStages.filterKeys { !it.endsWith("Nanos") })
        assertEquals(baseline.stats.openTheory.openTheoryChecks, treatment.stats.openTheory.openTheoryChecks)
        assertEquals(baseline.stats.smt.sourceLp.operations, treatment.stats.smt.sourceLp.operations)
        assertEquals(baseline.stats.smt.sourceLp.floatWork, treatment.stats.smt.sourceLp.floatWork)
        assertTrue(capture.records.any { it.parentQuery != null })
        assertTrue(capture.records.any { it.refutation != null })
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
        assertEquals(listOf(enabled.scope, guarded.scope, enabled.scope), capture.records.map { it.numeric.scope })
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
    fun `delivery should keep the latest query after the archive fills`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        capture.deliver(10_000L, 1_000_000L)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
            authority.advance(source, Cancellation.Never, cuts = true)
        }

        assertEquals(1, capture.records.size)
        assertTrue(capture.limited)
        val delivered = assertNotNull(capture.delivered)
        assertEquals(1, delivered.id)
        assertEquals(BigFraction.ONE, delivered.point?.get(x))
    }

    @Test
    fun `eviction should not lower the cumulative construction ledger`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        capture.deliver(10_000L, 1_000_000L)
        repeat(2) { capture.record(source, null, null, null, LinearAuthorityResult()) }
        val entries = capture.constructedEntries
        val bytes = capture.constructedBytes

        capture.stopDelivery()

        assertNull(capture.delivered)
        assertTrue(entries > capture.entries && bytes > capture.bytes)
        assertEquals(entries, capture.constructedEntries)
        assertEquals(bytes, capture.constructedBytes)
    }

    @Test
    fun `a delivery-only record should spend on references instead of copied rows`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        capture.deliver(10_000L, 1_000_000L)
        capture.record(source, null, null, null, LinearAuthorityResult())
        val archived = capture.constructedBytes

        capture.record(source, null, null, null, LinearAuthorityResult())

        assertNotNull(capture.delivered)
        assertTrue(capture.constructedBytes - archived < archived)
    }

    @Test
    fun `a query beyond the delivery allowance should be reported as oversized`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        capture.deliver(1L, 1_000_000L)

        capture.record(source, null, null, null, LinearAuthorityResult())

        assertTrue(capture.deliveryOverflowed)
        assertNull(capture.delivered)
        assertEquals(1, capture.records.size)
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

    @Test
    fun `a requested projection should match the full record on its requested fields`() {
        for (request in listOf(installed, LinearAuthorityFeedbackRequest(refutation = true),
            LinearAuthorityFeedbackRequest())) {
            val full = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply { deliver(10_000L, 1_000_000L) }
            val narrow = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
                deliver(10_000L, 1_000_000L, request)
            }
            for (mask in listOf<Set<Int>?>(emptySet(), null)) {
                val (expected, actual) = listOf(full, narrow).map { capture ->
                    LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
                        authority.capture = capture
                        authority.advance(source, Cancellation.Never, cuts = true, cutMask = mask)
                    }
                    assertNotNull(capture.delivered)
                }

                assertEquals(request, actual.projection)
                assertEquals(expected.state, actual.state)
                assertEquals(expected.context, actual.context)
                assertEquals(expected.point.takeIf { request.point }, actual.point)
                assertEquals(expected.refutation.takeIf { request.refutation }, actual.refutation)
                val kept = if (request.cuts == LinearAuthorityFeedbackRequest.Cuts.None) emptyList()
                    else expected.cuts.filter { it.installed && it.checked && it.ownerActive }
                assertEquals(kept.map { listOf(it.id, it.inequality, it.installed, it.checked, it.ownerActive) },
                    actual.cuts.map { listOf(it.id, it.inequality, it.installed, it.checked, it.ownerActive) })
                assertTrue(actual.cuts.all { it.violation == null })
                for ((reference, cut) in kept.zip(actual.cuts)) {
                    assertEquals(full.violation(expected, reference), narrow.violation(actual, cut))
                }
            }
        }
    }

    @Test
    fun `a requested projection should never evaluate unrequested cuts`() {
        val full = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply { deliver(10_000L, 1_000_000L) }
        val narrow = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
            deliver(10_000L, 1_000_000L, installed)
        }

        for (capture in listOf(full, narrow)) {
            LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
                authority.capture = capture
                authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
            }
        }

        assertEquals(1L, full.evaluatedCuts)
        assertEquals(0L, narrow.evaluatedCuts)
        assertTrue(narrow.constructedBytes < full.constructedBytes)
    }

    @Test
    fun `a requested record should reference its scope condition instead of charging its bounds`() {
        val bounded = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO),
            upper = mapOf(x to BigFraction.ONE))))
        fun charged(request: LinearAuthorityFeedbackRequest, context: SourceProofContext): Long {
            val capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
                deliver(10_000L, 1_000_000L, request)
            }
            capture.record(context, null, null, null, LinearAuthorityResult())
            return capture.constructedBytes
        }

        assertEquals(charged(installed, source), charged(installed, bounded))
        assertTrue(charged(LinearAuthorityFeedbackRequest.Full, source) <
            charged(LinearAuthorityFeedbackRequest.Full, bounded))
    }

    @Test
    fun `queries after an archive allowance overflow should be delivery-only`() {
        val bounded = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO),
            upper = mapOf(x to BigFraction.ONE))))
        val single = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never).apply {
            record(source, null, null, null, LinearAuthorityResult())
        }.bytes
        val capture = LinearAuthorityCapture(64, 10_000L, 2 * single + 1, Cancellation.Never).apply {
            deliver(10_000L, 1_000_000L, installed)
        }

        for (context in listOf(source, bounded, source)) {
            capture.record(context, null, null, null, LinearAuthorityResult())
        }

        assertEquals(1, capture.records.size)
        assertEquals(installed, assertNotNull(capture.delivered).projection)
    }

    @Test
    fun `on-demand evaluation should debit the cumulative construction ledger`() {
        val capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
            deliver(10_000L, 1_000_000L, installed)
        }
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true)
        }
        val record = assertNotNull(capture.delivered)
        val bytes = capture.constructedBytes

        val evaluated = capture.violation(record, record.cuts.single())

        assertEquals(LinearAuthorityCutViolation.Available(BigFraction.ZERO), evaluated)
        assertTrue(capture.constructedBytes > bytes)
        assertEquals(1L, capture.evaluatedCuts)
    }

    @Test
    fun `on-demand evaluation beyond the delivered allowance should be oversized`() {
        val measured = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
            deliver(10_000L, 1_000_000L, installed)
        }
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = measured
            authority.advance(source, Cancellation.Never, cuts = true)
        }
        val capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
            deliver(10_000L, measured.constructedBytes - 64L, installed)
        }
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true)
        }
        val record = assertNotNull(capture.delivered)

        val evaluated = capture.violation(record, record.cuts.single())

        assertEquals(LinearAuthorityCutViolation.Unavailable(LinearAuthorityCutViolation.Reason.Oversized), evaluated)
        assertTrue(capture.delivered === record)
    }

    @Test
    fun `on-demand evaluation should decline once its query is replaced or released`() {
        val capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation.Never).apply {
            deliver(10_000L, 1_000_000L, installed)
        }
        val (replaced, released) = LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true)
            val first = assertNotNull(capture.delivered)
            authority.advance(source, Cancellation.Never, cuts = true, cutMask = emptySet())
            authority.advance(source, Cancellation.Never, cuts = true)
            first to assertNotNull(capture.delivered).also { capture.stopDelivery() }
        }

        for (record in listOf(replaced, released)) {
            assertEquals(LinearAuthorityCutViolation.Unavailable(LinearAuthorityCutViolation.Reason.Released),
                capture.violation(record, record.cuts.single()))
        }
        assertEquals(0L, capture.evaluatedCuts)
    }

    @Test
    fun `on-demand evaluation should decline after cancellation`() {
        var stopped = false
        val capture = LinearAuthorityCapture(0, 0L, 0L, Cancellation { stopped }).apply {
            deliver(10_000L, 1_000_000L, installed)
        }
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advance(source, Cancellation.Never, cuts = true)
        }
        val record = assertNotNull(capture.delivered)
        stopped = true

        val evaluated = capture.violation(record, record.cuts.single())

        assertEquals(LinearAuthorityCutViolation.Unavailable(LinearAuthorityCutViolation.Reason.Cancelled), evaluated)
    }
}
