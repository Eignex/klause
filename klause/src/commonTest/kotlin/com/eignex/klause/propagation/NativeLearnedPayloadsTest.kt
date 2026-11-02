package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeLearnedPayloadsTest {
    @Test
    fun `small cleanup slices should restore directory capacity beside surviving payloads`() {
        val payloads = NativeLearnedPayloads()
        repeat(64) { payloads.add(intArrayOf(0, 2)) }
        val bytes = payloads.primitiveBytes
        val directory = payloads.directoryCapacity
        val references = payloads.referenceCapacity
        val first = payloads[0]
        repeat(20_000) { payloads.add(intArrayOf(4, 6)) }
        for (index in 64 until payloads.size) payloads.release(index)
        assertTrue(payloads.primitiveBytes > bytes)

        repeat(512) {
            val budget = ReclamationPreparation(40L, 256L, { false }, {})
            try { payloads.sweepDirectory(budget) } catch (_: ReclamationPreparation.Deferred) { }
            assertTrue(budget.work <= 40L)
        }

        assertEquals(bytes, payloads.primitiveBytes)
        assertEquals(directory, payloads.directoryCapacity)
        assertEquals(references, payloads.referenceCapacity)
        assertSame(first, payloads[0])
        assertEquals(0, payloads.length(64))
    }

    @Test
    fun `cancellation after directory funding should preserve the pending owner`() {
        val payloads = NativeLearnedPayloads()
        repeat(128) { payloads.add(intArrayOf(0, 2)) }
        for (index in 64..127) payloads.release(index)
        val bytes = payloads.primitiveBytes
        val directory = payloads.directoryCapacity
        var work = 0L
        val cancelled = ReclamationPreparation(24L, 256L, { work >= 24L }, { work += it })

        assertFailsWith<ReclamationPreparation.Deferred> { payloads.sweepDirectory(cancelled) }

        assertEquals(24L, work)
        assertEquals(bytes, payloads.primitiveBytes)
        assertEquals(directory, payloads.directoryCapacity)
        payloads.sweepDirectory(ReclamationPreparation(24L, 256L, { false }, {}))
        assertTrue(payloads.primitiveBytes < bytes)
        assertTrue(payloads.directoryCapacity < directory)
    }

    @Test
    fun `a revived queued page should survive later directory cleanup`() {
        val payloads = NativeLearnedPayloads()
        repeat(192) { payloads.add(intArrayOf(0, 2)) }
        val source = payloads[128]
        for (index in 64..127) payloads.release(index)
        payloads.move(128, 64)

        payloads.sweepDirectory(ReclamationPreparation(24L, 256L, { false }, {}))

        assertSame(source, payloads[64])
        assertEquals(0, payloads.length(128))
    }

    @Test
    fun `directory observations should retain only the required payload and branch path`() {
        val payloads = NativeLearnedPayloads()
        repeat(128) { payloads.add(intArrayOf(0, 2)) }
        val sample = SharingMemoryObservation(10_000L, 4096, { false }, {})

        val values = assertNotNull(sample.snapshot {
            payloads.sharingMemory(sample, true, false) { it == 0 }
        }.completeValues)

        assertEquals(payloads.primitiveBytes + payloads.capacityBytes, values["ownedPrimitiveBytes"])
        assertEquals(payloads.referenceCapacity + payloads.directoryCapacity + 15L, values["ownedReferenceSlots"])
        assertTrue(values.getValue("protectedPrimitiveBytes") < values.getValue("ownedPrimitiveBytes"))
        assertTrue(values.getValue("protectedReferenceSlots") < values.getValue("ownedReferenceSlots"))
    }
}
