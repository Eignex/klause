package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearnedIndexTest {
    @Test
    fun `deleting internal keys should preserve ordered lookups across rotations`() {
        val index = LearnedIndex<String>()
        for (key in listOf(4L, -2L, 8L, 0L, 6L, 10L, -4L, 2L)) index.put(key, key.toString())

        index.remove(4L)
        index.remove(-2L)
        index.remove(8L)

        assertEquals(5, index.size)
        assertNull(index[4L])
        assertEquals("2", index.next(0L))
        assertEquals("6", index.next(10L, descending = true))
        val retained = ArrayList<Long>()
        index.forEach { key, _ -> retained.add(key) }
        assertEquals(listOf(-4L, 0L, 2L, 6L, 10L), retained)
    }

    @Test
    fun `deleting all entries should release index primitive and reference owners`() {
        val index = LearnedIndex<String>()
        repeat(64) { index.put(it.toLong(), it.toString()) }

        repeat(64) { index.remove(it.toLong()) }

        val sample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val values = requireNotNull(sample.snapshot { index.memory(sample) { false } }.completeValues)
        assertEquals(0L, values.getValue("ownedPrimitiveBytes"))
        assertEquals(1L, values.getValue("ownedReferenceSlots"))
        assertEquals(8L, index.ownedBytes)
    }
}
