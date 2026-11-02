package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableIntObjectIndexTest {
    @Test
    fun `overwriting extreme keys should retain independent values`() {
        val index = MutableIntObjectIndex<String>()
        index.put(0, "zero")
        index.put(Int.MAX_VALUE, "last")
        index.put(16, "middle")

        index.put(0, "updated")

        assertEquals(3, index.size)
        assertEquals("updated", index[0])
        assertEquals("middle", index[16])
        assertEquals("last", index[Int.MAX_VALUE])
        assertNull(index[15])
    }

    @Test
    fun `ordered lookup should cross sparse prefixes and stop beyond the last key`() {
        val index = MutableIntObjectIndex<String>()
        val keys = listOf(0, 2, 4, 8, 16, 1 shl 30, Int.MAX_VALUE)
        for (key in keys.reversed()) index.put(key, key.toString())

        for (from in listOf(0L, 1L, 3L, 6L, 17L, (1 shl 30).toLong(), Int.MAX_VALUE.toLong() + 1L)) {
            val next = index.firstAtOrAfter(from) {}
            assertEquals(keys.firstOrNull { it.toLong() >= from }, next?.key)
        }
    }

    @Test
    fun `deleting sparse rows should restore capacity beside surviving extreme keys`() {
        val index = MutableIntObjectIndex<String>()
        index.put(0, "first")
        index.put(Int.MAX_VALUE, "last")
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        for (key in 1..20_000) index.put(key, key.toString())

        for (key in 1..20_000) {
            val slot = assertNotNull(index.firstAtOrAfter(key.toLong()) {})
            assertEquals(key, slot.key)
            assertTrue(index.remove(slot, {}, {}))
        }

        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals(2, index.size)
        assertEquals("first", index[0])
        assertEquals("last", index[Int.MAX_VALUE])
    }

    @Test
    fun `cancellation after funding should preserve both entries and their capacity`() {
        val index = MutableIntObjectIndex<String>()
        index.put(0, "first")
        index.put(Int.MAX_VALUE, "last")
        val slot = assertNotNull(index.firstAtOrAfter(0L) {})
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        var funded = 0L

        assertFailsWith<IllegalStateException> {
            index.remove(slot, { funded += it }, { error("cancelled") })
        }

        assertTrue(funded > 0L)
        assertEquals(2, index.size)
        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals("first", index[0])
        assertEquals("last", index[Int.MAX_VALUE])
    }

    @Test
    fun `retired and foreign slots should not remove a replacement entry`() {
        val index = MutableIntObjectIndex<String>()
        index.put(7, "old")
        val old = assertNotNull(index.firstAtOrAfter(7L) {})
        assertTrue(index.remove(old, {}, {}))
        index.put(7, "new")
        val foreign = MutableIntObjectIndex<String>()
        foreign.put(7, "foreign")
        val other = assertNotNull(foreign.firstAtOrAfter(7L) {})

        assertFalse(index.remove(old, {}, {}))
        assertFalse(index.remove(other, {}, {}))

        assertEquals("new", index[7])
        assertEquals("foreign", foreign[7])
    }
}
