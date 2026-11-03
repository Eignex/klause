package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeLearnedMetadataTest {
    @Test
    fun `paired watch positions should preserve both signed integer coordinates`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }

        for (index in listOf(0, 16)) {
            for ((first, second) in listOf(Int.MIN_VALUE to Int.MAX_VALUE, -1 to 0, 1 to Int.MIN_VALUE)) {
                metadata[index, NativeLearnedMetadata.WATCH_FIRST] = first
                metadata[index, NativeLearnedMetadata.WATCH_SECOND] = second

                val positions = metadata.watchPositions(index)

                assertEquals(first, (positions ushr 32).toInt())
                assertEquals(second, positions.toInt())
            }
        }
    }

    @Test
    fun `paired watch positions should return zeros for a released row`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        metadata[16, NativeLearnedMetadata.WATCH_FIRST] = 3
        metadata[16, NativeLearnedMetadata.WATCH_SECOND] = 8
        metadata.release(16)

        val positions = metadata.watchPositions(16)

        assertEquals(0L, positions)
    }

    @Test
    fun `a missing paired watch read should fund both directory lookups`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        val budget = ReclamationPreparation(1L, 256L, { false }, {})

        assertFailsWith<ReclamationPreparation.Deferred> {
            metadata.usingBudget(budget) { metadata.watchPositions(32) }
        }

        assertEquals(1L, budget.work)
        val retry = ReclamationPreparation(2L, 256L, { false }, {})
        assertEquals(0L, metadata.usingBudget(retry) { metadata.watchPositions(32) })
        assertEquals(2L, retry.work)
    }

    @Test
    fun `a missing paired watch read should observe cancellation between lookups`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        var charged = 0L
        val budget = ReclamationPreparation(16L, 256L, { charged > 0L }, { charged += it })

        assertFailsWith<ReclamationPreparation.Deferred> {
            metadata.usingBudget(budget) { metadata.watchPositions(32) }
        }

        assertEquals(1L, charged)
        assertEquals(1L, budget.work)
    }

    @Test
    fun `an unfunded paired watch lookup should preserve coordinates for a later grant`() {
        val metadata = NativeLearnedMetadata()
        repeat(20_000) { metadata.add(9, false, -1, it.toLong()) }
        metadata[16, NativeLearnedMetadata.WATCH_FIRST] = Int.MIN_VALUE
        metadata[16, NativeLearnedMetadata.WATCH_SECOND] = Int.MAX_VALUE
        metadata[19_999, NativeLearnedMetadata.WATCH_FIRST]
        val budget = ReclamationPreparation(0L, 256L, { false }, {})

        assertFailsWith<ReclamationPreparation.Deferred> {
            metadata.usingBudget(budget) { metadata.watchPositions(16) }
        }

        assertEquals(0L, budget.work)
        val retry = ReclamationPreparation(16L, 256L, { false }, {})
        val positions = metadata.usingBudget(retry) { metadata.watchPositions(16) }
        assertEquals(Int.MIN_VALUE, (positions ushr 32).toInt())
        assertEquals(Int.MAX_VALUE, positions.toInt())
        assertTrue(retry.work in 1L..16L)
    }

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

    @Test
    fun `an unused startup reservation should release before any row is admitted`() {
        val metadata = NativeLearnedMetadata()
        val before = metadata.primitiveBytes

        metadata.releaseEmptyReservation()

        assertTrue(metadata.primitiveBytes < before)
        assertEquals(0, metadata.size)
        metadata.add(2, true, 4, 9L)
        assertEquals(2, metadata[0, NativeLearnedMetadata.LBD])
        assertEquals(9L, metadata.admission(0))
    }

}
