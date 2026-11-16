package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchedClauseStoreTest {
    @Test
    fun `protected clauses should retain their allocation owners without protecting unrelated postings`() {
        val store = WatchedClauseStore()
        val host = SearchSession(emptyList())
        val first = store.add(intArrayOf(0, 2), 3)
        val second = store.add(intArrayOf(4, 6), 3)
        store.attach(first, host)
        store.attach(second, host)
        val ordinarySample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val ordinary = requireNotNull(ordinarySample.snapshot { store.memory(ordinarySample) }.completeValues)
        val firstSample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val firstProtected = requireNotNull(firstSample.snapshot {
            store.memory(firstSample) { it == first }
        }.completeValues)
        val allSample = SharingMemoryObservation(100_000L, 1024, { false }, {})

        val allProtected = requireNotNull(allSample.snapshot { store.memory(allSample) { true } }.completeValues)

        assertEquals(ordinary.getValue("ownedPrimitiveBytes"), firstProtected.getValue("ownedPrimitiveBytes"))
        assertEquals(0L, ordinary.getValue("protectedPrimitiveBytes"))
        assertEquals(firstProtected.getValue("ownedReferenceSlots"), firstProtected.getValue("protectedReferenceSlots"))
        assertEquals(allProtected.getValue("ownedPrimitiveBytes"), allProtected.getValue("protectedPrimitiveBytes"))
        assertEquals(allProtected.getValue("ownedReferenceSlots"), allProtected.getValue("protectedReferenceSlots"))
        assertTrue(firstProtected.getValue("protectedPrimitiveBytes") > 2L * Int.SIZE_BYTES)
        assertTrue(allProtected.getValue("protectedPrimitiveBytes") -
            firstProtected.getValue("protectedPrimitiveBytes") > 2L * Int.SIZE_BYTES)
    }
}
