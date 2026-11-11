package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JointRepairObservationTest {
    @Test
    fun `proposal capture should retain exact pins and actual free coordinates`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), Cancellation.Never, records::add)
        val centers = mutableListOf(BigFraction.ONE, BigFraction.ofLong(2))
        val pins = linkedMapOf(0 to BigFraction.ONE)
        val bools = linkedMapOf(0 to true)

        capture.proposal(7, 2, 0, 1, 5, 2, "checked-pooled", centers, 2, null, listOf(1),
            ExactNeighborhoodCondition(bools, pins, pins, BigFraction.ofLong(3)))
        centers[0] = BigFraction.ZERO
        pins.clear()
        bools.clear()

        val record = records.single()
        assertEquals(mapOf(0 to BigFraction.ONE, 1 to BigFraction.ofLong(2)), record.centers)
        assertEquals(mapOf(0 to BigFraction.ONE), record.lower)
        assertEquals(record.lower, record.upper)
        assertEquals(mapOf(0 to true), record.bools)
        assertEquals(listOf(1), record.freed)
        assertEquals(listOf(1), record.refutationRoots)
        assertEquals(5, record.requestedCount)
        assertEquals(2, record.effectiveCount)
        assertEquals(2, record.operator)
        assertEquals(0L, record.schedulingRound)
        assertEquals(1L, record.proposalRound)
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `feedback capture should preserve bound direction and missing condition values`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), Cancellation.Never, records::add)
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val assumptions = linkedSetOf(SourceDerivation.Assumption(integer, false),
            SourceDerivation.Assumption(integer, true), SourceDerivation.Assumption(real, true))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE),
            mapOf(0 to BigFraction.ofLong(2)))

        capture.feedback(3, 2, "Guidance", assumptions, condition, 1)
        assumptions.clear()

        assertEquals(listOf(JointRepairObservedBound(integer, false, BigFraction.ONE),
            JointRepairObservedBound(integer, true, BigFraction.ofLong(2)),
            JointRepairObservedBound(real, true, null)), records.single().support)
        assertEquals(3L, records.single().proposal)
        assertEquals(2L, records.single().query)
    }

    @Test
    fun `capture should spend containing funding without renewing a paused query`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), Cancellation.Never, records::add)

        capture.event(1, 1, "query", "opened")

        assertEquals(1, records.size)
        assertTrue(funding.spent > 0)
        assertTrue(funding.paused)
        assertFalse(funding.exhausted)
        assertEquals(0L, funding.availableWork)
    }

    @Test
    fun `spent funding should withhold a capture without charging more work`() {
        val funding = SourceLpFunding(0, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), Cancellation.Never, records::add)

        capture.event(1, 1, "query", "opened")

        assertTrue(records.isEmpty())
        assertEquals(0L, funding.spent)
        assertEquals(1L, capture.observations()["jointRepairObservationDeclinedFunding"])
    }

    @Test
    fun `cancelled capture should withhold metadata without charging work`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = funding), Cancellation { true }, records::add)

        capture.event(1, 1, "query", "opened")

        assertTrue(records.isEmpty())
        assertEquals(0L, funding.spent)
        assertEquals(1L, capture.observations()["jointRepairObservationDeclinedCancelled"])
    }

    @Test
    fun `oversized scalars should withhold the entire proposal`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), Cancellation.Never, records::add)
        val value = BigFraction.of(bigIntOf(1) shl 4096, BIG_ONE)

        capture.proposal(1, 0, 1, 1, 1, 1, "stored", listOf(value), 1, null, emptyList(),
            ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()))

        assertTrue(records.isEmpty())
        assertEquals(1L, capture.observations()["jointRepairObservationDeclinedScalar"])
        assertEquals(1L, capture.observations()["jointRepairObservationDropped"])
    }

    @Test
    fun `record exhaustion should stop capture and retain dropped event counts`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), Cancellation.Never, records::add)
        repeat(64) { capture.event(it.toLong(), 0, "result", "undecided") }
        val spent = funding.spent

        repeat(3) { capture.event(65, 0, "result", "undecided") }

        assertEquals(64, records.size)
        assertEquals(spent, funding.spent)
        assertEquals(3L, capture.observations()["jointRepairObservationDropped"])
        assertEquals(1L, capture.observations()["jointRepairObservationDeclinedRecords"])
    }

    @Test
    fun `large proposals should stay within cumulative capture bounds`() {
        val funding = SourceLpFunding(10_000_000, 10_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), Cancellation.Never, records::add)
        val centers = List(16_385) { BigFraction.ONE }

        capture.proposal(1, 2, 0, 0, centers.size, centers.size, "stored", centers, centers.size, null,
            emptyList(), ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()))

        assertTrue(records.isEmpty())
        assertTrue(capture.observations().getValue("jointRepairObservationEntries") <= 16_384)
        assertTrue(capture.observations().getValue("jointRepairObservationBytes") <= 1_048_576)
        assertTrue(funding.allocated <= 1_048_576)
        assertEquals(1L, capture.observations()["jointRepairObservationDropped"])
    }
}
