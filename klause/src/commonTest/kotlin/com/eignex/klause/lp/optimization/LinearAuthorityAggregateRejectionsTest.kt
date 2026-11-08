package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearAuthorityAggregateRejectionsTest {
    private val cut = LinearAuthorityCut(0, SourceInequality(
        mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.ONE), BigFraction.ZERO),
        SourceDerivation.Premise("upper"))

    @Test
    fun `event capture should stop at its remaining observation work`() {
        val funding = SourceLpFunding(100_000L, 1_000_000L).apply { grant(100_000L) }
        val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation.Never,
            maximumWork = 1L, maximumAllocation = 512L)

        capture.observe("AggregateMissingBounds", 3L)
        capture.observe("AggregateMissingBounds", 2L)

        assertEquals(mapOf("AggregateMissingBounds" to 3L), capture.events)
        assertTrue(capture.limited)
        assertEquals(1L, capture.work)
        assertEquals(capture.work, funding.spent)
    }

    @Test
    fun `rejection capture should preserve the inequality and its point projection`() {
        val funding = SourceLpFunding(100_000L, 1_000_000L).apply { grant(100_000L) }
        val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation.Never)

        capture.record(cut, -1.0, 1.0, -1.0)

        val record = capture.records.single()
        assertEquals(cut.inequality, record.inequality)
        assertEquals(cut.owners, record.owners)
        assertEquals(-1.0, record.activity)
        assertEquals(1.0, record.normSquared)
        assertEquals(-1.0, record.efficacy)
        assertFalse(capture.limited)
        assertTrue(capture.work > 0L && capture.allocation > 0L)
    }

    @Test
    fun `rejection capture should retain a bounded prefix and report incomplete coverage`() {
        val funding = SourceLpFunding(100_000L, 1_000_000L).apply { grant(100_000L) }
        val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation.Never, 1)

        capture.record(cut, -1.0, 1.0, -1.0)
        capture.record(cut, -2.0, 1.0, -2.0)

        assertEquals(-1.0, capture.records.single().efficacy)
        assertEquals(2L, capture.attempted)
        assertTrue(capture.limited)
        assertTrue(funding.spent <= 100_000L && funding.allocated <= 1_000_000L)
    }

    @Test
    fun `unfunded rejection capture should publish no record`() {
        for ((work, allocation) in listOf(1L to 1_000_000L, 100_000L to 1L)) {
            val funding = SourceLpFunding(work, allocation).apply { grant(work) }
            val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation.Never)

            capture.record(cut, -1.0, 1.0, -1.0)

            assertTrue(capture.records.isEmpty())
            assertTrue(capture.limited)
            assertTrue(funding.spent <= work && funding.allocated <= allocation)
        }
    }

    @Test
    fun `rejection capture should decline scalars beyond its retained width`() {
        val funding = SourceLpFunding(100_000L, 1_000_000L).apply { grant(100_000L) }
        val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation.Never)
        val wide = LinearAuthorityCut(0, SourceInequality(
            mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.of(BIG_ONE shl 300, BIG_ONE)),
            BigFraction.ZERO), SourceDerivation.Premise("wide"))

        capture.record(wide, -1.0, 1.0, -1.0)

        assertTrue(capture.records.isEmpty())
        assertTrue(capture.limited)
    }

    @Test
    fun `cancelled rejection capture should publish no record`() {
        val funding = SourceLpFunding(100_000L, 1_000_000L).apply { grant(100_000L) }
        val capture = LinearAuthorityAggregateRejections(SourceLpBudget(funding = funding), Cancellation { true })

        capture.record(cut, -1.0, 1.0, -1.0)

        assertTrue(capture.records.isEmpty())
        assertTrue(capture.limited)
        assertEquals(0L, funding.spent)
        assertEquals(0L, funding.allocated)
    }
}
