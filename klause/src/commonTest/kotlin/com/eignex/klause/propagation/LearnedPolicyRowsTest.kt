package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.arrays.emptyIntArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearnedPolicyRowsTest {
    @Test
    fun `queued metadata release should restore ownership beside a surviving first page`() {
        val rows = LearnedPolicyRows(6, 8, true, null)
        val clause = ClausePropagator(intArrayOf(0, 1), emptyIntArray(), intArrayOf(0, 2))
        repeat(16) { rows.add(16, 2, true, -1, it.toLong(), clause) }
        val bytes = rows.primitiveBytes
        val references = rows.referenceCapacity
        repeat(20_000) { rows.add(16, 9, false, 7, it.toLong() + 16L, clause) }
        for (index in 16 until rows.size) rows.enqueueRetired(index)

        repeat(128) {
            val budget = ReclamationPreparation(32_768L, 4096L, { false }, {})
            try {
                while (rows.hasRetiredRows) {
                    budget.visit(32L, 16L)
                    budget.visit(0L)
                    rows.releaseRetired()
                }
                rows.sweepDirectory(budget)
            } catch (_: ReclamationPreparation.Deferred) { }
            assertTrue(budget.work <= 32_768L)
        }

        assertEquals(bytes, rows.primitiveBytes)
        assertEquals(references, rows.referenceCapacity)
        assertEquals(20_016, rows.size)
        assertEquals(15L, rows.admission(15))
        assertEquals(8, rows[16, GeneralLearnedRows.STAGES])
        assertEquals(-1, rows.retiredHeadIndex)
    }

    @Test
    fun `a large metadata observation should use bounded identity scratch`() {
        val rows = NativeLearnedMetadata()
        repeat(20_000) { rows.add(9, it == 0, -1, it.toLong()) }
        val sample = SharingMemoryObservation(32_768L, 4096, { false }, {})

        val memory = sample.snapshot { rows.sharingMemory(sample, true) { it == 0 } }

        val values = assertNotNull(memory.completeValues)
        assertEquals(rows.primitiveBytes, values["ownedPrimitiveBytes"])
        assertEquals(rows.referenceCapacity, values["ownedReferenceSlots"])
        assertTrue(values.getValue("protectedPrimitiveBytes") < values.getValue("ownedPrimitiveBytes"))
        assertTrue(memory.scratchEntries < 16)
    }

    @Test
    fun `cancellation after header funding should retain a released metadata page`() {
        val rows = NativeLearnedMetadata()
        repeat(32) { rows.add(9, false, -1, it.toLong()) }
        for (index in 16..31) rows.release(index)
        val bytes = rows.primitiveBytes
        val references = rows.referenceCapacity
        var work = 0L
        val budget = ReclamationPreparation(24L, 256L, { work >= 24L }, { work += it })

        assertFailsWith<ReclamationPreparation.Deferred> { rows.sweepDirectory(budget) }

        assertEquals(24L, work)
        assertEquals(bytes, rows.primitiveBytes)
        assertEquals(references, rows.referenceCapacity)
        rows.sweepDirectory(ReclamationPreparation(24L, 256L, { false }, {}))
        assertTrue(rows.primitiveBytes < bytes)
        assertTrue(rows.referenceCapacity < references)
    }

    @Test
    fun `an unfunded cold lookup should preserve metadata for a later grant`() {
        val rows = NativeLearnedMetadata()
        repeat(66_000) { rows.add(it, false, -1, it.toLong() + 1L) }
        val budget = ReclamationPreparation(0L, 256L, { false }, {})

        assertFailsWith<ReclamationPreparation.Deferred> {
            rows.usingBudget(budget) { rows[16, NativeLearnedMetadata.LBD] }
        }

        assertEquals(0L, budget.work)
        val retry = ReclamationPreparation(16L, 256L, { false }, {})
        val value = rows.usingBudget(retry) { rows[16, NativeLearnedMetadata.LBD] }
        assertEquals(16, value)
        assertTrue(retry.work in 1L..16L)
        assertEquals(17L, rows.admission(16))
    }

    @Test
    fun `a queued metadata page should retain a remapped live row`() {
        val rows = NativeLearnedMetadata()
        repeat(48) { rows.add(it, false, -1, it.toLong() + 1L) }
        for (index in 16..31) rows.release(index)
        rows.move(32, 16)

        rows.sweepDirectory(ReclamationPreparation(24L, 256L, { false }, {}))

        assertEquals(32, rows[16, NativeLearnedMetadata.LBD])
        assertEquals(33L, rows.admission(16))
    }
}
