package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeLearnedMetadataTest {
    @Test
    fun `releasing an empty page should preserve other learned rows`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(it + 1, it == 16, it, it.toLong() + 1L) }
        val before = metadata.primitiveBytes

        repeat(16) { metadata.release(it) }

        assertTrue(metadata.primitiveBytes < before)
        assertEquals(32, metadata.size)
        assertEquals(4, metadata[0, NativeLearnedMetadata.STAGES])
        assertEquals(17, metadata[16, NativeLearnedMetadata.LBD])
        assertEquals(1, metadata[16, NativeLearnedMetadata.PERMANENT])
        assertEquals(17L, metadata.admission(16))
    }

    @Test
    fun `moving into a released page should preserve every policy value`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(it + 1, it == 16, it, it.toLong() + 1L) }
        for (column in 0..8) metadata[16, column] = column + 10
        repeat(16) { metadata.release(it) }

        metadata.move(16, 0)
        metadata.truncateTo(1)

        for (column in 0..8) assertEquals(column + 10, metadata[0, column])
        assertEquals(17L, metadata.admission(0))
        assertEquals(1, metadata.size)
    }

    @Test
    fun `appending after truncation should clear the former row state`() {
        val metadata = NativeLearnedMetadata()
        repeat(4) { metadata.add(9, false, it, it.toLong()) }
        metadata[1, NativeLearnedMetadata.STAGES] = 15
        metadata[1, NativeLearnedMetadata.BOOL_REASONS] = 2
        metadata[1, NativeLearnedMetadata.WATCH_SECOND] = 8
        metadata.truncateTo(1)

        metadata.add(2, true, 20, 42L)

        assertEquals(0, metadata[1, NativeLearnedMetadata.STAGES])
        assertEquals(0, metadata[1, NativeLearnedMetadata.BOOL_REASONS])
        assertEquals(0, metadata[1, NativeLearnedMetadata.WATCH_SECOND])
        assertEquals(2, metadata[1, NativeLearnedMetadata.LBD])
        assertEquals(42L, metadata.admission(1))
    }

    @Test
    fun `a protected row should protect its whole metadata allocation`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, it, it.toLong()) }
        val sample = SharingMemoryObservation(10_000L, 4096, { false }, {})

        val observed = assertNotNull(sample.snapshot {
            metadata.sharingMemory(sample, true) { it == 0 }
        }.completeValues)

        assertEquals(metadata.primitiveBytes, observed.getValue("ownedPrimitiveBytes"))
        assertTrue(observed.getValue("protectedPrimitiveBytes") > 16L)
        assertTrue(observed.getValue("protectedPrimitiveBytes") < observed.getValue("ownedPrimitiveBytes"))
    }
    @Test
    fun `capacity growth should keep each observed allocation identity immutable`() {
        val metadata = NativeLearnedMetadata()
        val sample = SharingMemoryObservation(10_000L, 4096, { false }, {})
        assertNotNull(sample.snapshot { metadata.sharingMemory(sample, false) { false } }.completeValues)
        repeat(32) { metadata.add(9, false, it, it.toLong()) }
        val before = assertNotNull(sample.snapshot {
            metadata.sharingMemory(sample, false) { false }
        }.completeValues)

        val protected = assertNotNull(sample.snapshot {
            metadata.sharingMemory(sample, true) { true }
        }.completeValues)

        assertEquals(before.getValue("ownedPrimitiveBytes"), protected.getValue("ownedPrimitiveBytes"))
        assertEquals(metadata.primitiveBytes, protected.getValue("protectedPrimitiveBytes"))
    }

}
