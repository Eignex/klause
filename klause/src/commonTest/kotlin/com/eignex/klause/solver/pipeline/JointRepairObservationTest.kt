package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JointRepairObservationTest {
    @Test
    fun `conditioned query should retain the actual scope beyond narrow masks`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), ExecutionControl.Never, records::add)
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 99)
        val row = SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ONE)
        val original = SourceScope(SourceRevision("original", "native"),
            objective = SourceObjective("original-objective", ObjectiveSense.MINIMIZE))
        val receiving = original.copy(revision = SourceRevision("private", "native"),
            objective = SourceObjective("private-objective", ObjectiveSense.MINIMIZE),
            condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE)).integerBound(row))

        capture.privateQuery(4, 7, original, receiving, BigFraction.ofLong(3), false)

        val record = records.single()
        val scoped = assertNotNull(record.privateQuery)
        assertEquals(4L, record.proposal)
        assertEquals(7L, record.query)
        assertEquals(BigFraction.ofLong(3), record.cutoff)
        assertEquals(original, scoped.working.scope)
        assertEquals(receiving, scoped.receiving.scope)
        assertEquals(setOf(row), scoped.receiving.integerRows)
        assertFalse(scoped.cuts)
    }

    @Test
    fun `proposal capture should retain exact pins and actual free coordinates`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)
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
    fun `declined selection capture should preserve its own center and boundary edge`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)
        val centers = mutableListOf(BigFraction.ONE, BigFraction.ZERO)
        val freed = linkedSetOf(0)
        val result = JointRepairNeighborhood.AdvisorySelection.Declined(
            JointRepairNeighborhood.AdvisorySelection.Limit.Width, 4, 0, 1, freed)
        val sample = Sample(booleanArrayOf(), longArrayOf(1, 0), doubleArrayOf(0.25))

        val metadata = capture.advisorySelection(9, 3, 0, true, centers, sample, 1, BigFraction.ONE,
            SourceRevision("captured-source", "native"), result, 7, "advisory-source")
        centers[0] = BigFraction.ZERO
        freed.clear()

        val record = records.single()
        assertEquals(mapOf(0 to BigFraction.ONE, 1 to BigFraction.ZERO), record.centers)
        assertEquals(listOf(0), record.freed)
        assertEquals(mapOf(0 to 0.25), record.realCenters)
        assertEquals(true, record.realCentersComplete)
        assertEquals("width-declined", record.disposition)
        assertEquals(JointRepairObservedSelection(9, 3, 0, true, 7, null, 4, 0, 1,
            "captured-source", "native"), metadata)
        assertEquals(metadata, record.selection)
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `nonfinite real guidance should make a captured center incomplete`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), ExecutionControl.Never, records::add)
        val sample = Sample(booleanArrayOf(), longArrayOf(0), doubleArrayOf(Double.NaN))

        capture.advisorySelection(1, 0, 0, false, listOf(BigFraction.ZERO), sample, 1, BigFraction.ONE,
            SourceRevision("captured-source", "native"), JointRepairNeighborhood.AdvisorySelection.Unavailable,
            0, "advisory-source")

        assertEquals(emptyMap(), records.single().realCenters)
        assertEquals(false, records.single().realCentersComplete)
    }

    @Test
    fun `feedback capture should preserve bound direction and missing condition values`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), ExecutionControl.Never, records::add)
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
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)

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
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)

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
            SourceLpBudget(funding = funding), ExecutionControl { true }, records::add)

        capture.event(1, 1, "query", "opened")

        assertTrue(records.isEmpty())
        assertEquals(0L, funding.spent)
        assertEquals(1L, capture.observations()["jointRepairObservationDeclinedCancelled"])
    }

    @Test
    fun `oversized scalars should withhold the entire proposal`() {
        val records = mutableListOf<JointRepairObservation>()
        val capture = JointRepairObservationCapture(
            SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)), ExecutionControl.Never, records::add)
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
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)
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
        val capture = JointRepairObservationCapture(SourceLpBudget(funding = funding), ExecutionControl.Never,
            records::add)
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
