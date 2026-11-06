package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeLearnedMetadataTest {
    @Test
    fun `live policy updates should preserve unrelated row values`() {
        val metadata = NativeLearnedMetadata()
        metadata.add(9, false, 12, 17L)
        metadata[0, NativeLearnedMetadata.STAGES] = 10
        metadata[0, NativeLearnedMetadata.BOOL_REASONS] = 3

        metadata.setTierIfLive(0, ClauseTier.MID.ordinal)
        metadata.clearStageIfLive(0, 8)

        assertEquals(ClauseTier.MID.ordinal, metadata[0, NativeLearnedMetadata.TIER])
        assertEquals(2, metadata[0, NativeLearnedMetadata.STAGES])
        assertEquals(9, metadata[0, NativeLearnedMetadata.LBD])
        assertEquals(3, metadata[0, NativeLearnedMetadata.BOOL_REASONS])
        assertEquals(12, metadata[0, NativeLearnedMetadata.ORIGIN])
        assertEquals(17L, metadata.admission(0))
    }

    @Test
    fun `retired policy updates should preserve row values`() {
        val metadata = NativeLearnedMetadata()
        metadata.add(9, false, -1, 1L)
        metadata[0, NativeLearnedMetadata.TIER] = ClauseTier.MID.ordinal
        metadata[0, NativeLearnedMetadata.STAGES] = 12

        metadata.setTierIfLive(0, ClauseTier.LOCAL.ordinal)
        metadata.clearStageIfLive(0, 8)

        assertEquals(ClauseTier.MID.ordinal, metadata[0, NativeLearnedMetadata.TIER])
        assertEquals(12, metadata[0, NativeLearnedMetadata.STAGES])
    }

    @Test
    fun `released policy updates should not revive a metadata page`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        repeat(16) { metadata.release(it + 16) }
        val before = metadata.primitiveBytes

        metadata.setTierIfLive(16, ClauseTier.MID.ordinal)
        metadata.clearStageIfLive(16, 8)

        assertEquals(4, metadata[16, NativeLearnedMetadata.STAGES])
        assertEquals(before, metadata.primitiveBytes)
        assertEquals(32, metadata.size)
    }

    @Test
    fun `unfunded cold policy updates should preserve metadata`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        metadata[16, NativeLearnedMetadata.STAGES] = 8
        repeat(2048) { metadata.add(9, false, -1, it.toLong() + 32L) }
        val budget = ReclamationPreparation(0L, 256L, { false }, {})

        metadata.usingBudget(budget) {
            assertFailsWith<ReclamationPreparation.Deferred> {
                metadata.setTierIfLive(16, ClauseTier.MID.ordinal)
            }
            assertFailsWith<ReclamationPreparation.Deferred> { metadata.clearStageIfLive(16, 8) }
        }

        assertEquals(0, metadata[16, NativeLearnedMetadata.TIER])
        assertEquals(8, metadata[16, NativeLearnedMetadata.STAGES])
        assertEquals(0L, budget.work)
    }

    @Test
    fun `cancelled cold policy updates should leave the page unavailable to an unfunded read`() {
        val metadata = NativeLearnedMetadata()
        repeat(32) { metadata.add(9, false, -1, it.toLong()) }
        metadata[16, NativeLearnedMetadata.STAGES] = 8
        repeat(2048) { metadata.add(9, false, -1, it.toLong() + 32L) }
        val cancelled = ReclamationPreparation(100_000L, 256L, { true }, {})
        val unfunded = ReclamationPreparation(0L, 256L, { false }, {})

        metadata.usingBudget(cancelled) {
            assertFailsWith<ReclamationPreparation.Deferred> {
                metadata.setTierIfLive(16, ClauseTier.MID.ordinal)
            }
            assertFailsWith<ReclamationPreparation.Deferred> { metadata.clearStageIfLive(16, 8) }
        }

        metadata.usingBudget(unfunded) {
            assertFailsWith<ReclamationPreparation.Deferred> { metadata[16, NativeLearnedMetadata.LBD] }
        }
        assertEquals(0, metadata[16, NativeLearnedMetadata.TIER])
        assertEquals(8, metadata[16, NativeLearnedMetadata.STAGES])
        assertEquals(0L, cancelled.work)
    }

    @Test
    fun `fused policy updates should preserve scalar cold charges and cancellation polls`() {
        val scalar = NativeLearnedMetadata()
        val fused = NativeLearnedMetadata()
        for (metadata in listOf(scalar, fused)) {
            repeat(32) { metadata.add(9, false, -1, it.toLong()) }
            metadata[16, NativeLearnedMetadata.STAGES] = 10
            repeat(2048) { metadata.add(9, false, -1, it.toLong() + 32L) }
        }
        val scalarCharges = mutableListOf<Long>()
        val fusedCharges = mutableListOf<Long>()
        var scalarPolls = 0
        var fusedPolls = 0
        val scalarBudget = ReclamationPreparation(100_000L, 256L, { scalarPolls++; false }, { scalarCharges += it })
        val fusedBudget = ReclamationPreparation(100_000L, 256L, { fusedPolls++; false }, { fusedCharges += it })

        scalar.usingBudget(scalarBudget) {
            if (scalar[16, NativeLearnedMetadata.STAGES] and 4 == 0) {
                scalar[16, NativeLearnedMetadata.TIER] = ClauseTier.MID.ordinal
            }
            if (scalar[16, NativeLearnedMetadata.STAGES] and 4 == 0) {
                scalar[16, NativeLearnedMetadata.STAGES] = scalar[16, NativeLearnedMetadata.STAGES] and 8.inv()
            }
        }
        fused.usingBudget(fusedBudget) {
            fused.setTierIfLive(16, ClauseTier.MID.ordinal)
            fused.clearStageIfLive(16, 8)
        }

        assertTrue(scalarBudget.work > 0L)
        assertEquals(scalarCharges, fusedCharges)
        assertEquals(scalarPolls, fusedPolls)
        for (column in 0..8) assertEquals(scalar[16, column], fused[16, column])
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
