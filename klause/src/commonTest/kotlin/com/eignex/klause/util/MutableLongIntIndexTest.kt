package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableLongIntIndexTest {
    @Test
    fun `extreme keys should retain independent overwritten values`() {
        val index = MutableLongIntIndex()
        index.put(Long.MIN_VALUE, 1)
        index.put(Long.MAX_VALUE, 2)
        index.put(0L, 3)

        index.put(Long.MIN_VALUE, 4)

        assertEquals(3, index.size)
        assertEquals(4, index.getOrDefault(Long.MIN_VALUE, -1))
        assertEquals(2, index.getOrDefault(Long.MAX_VALUE, -1))
        assertEquals(3, index.getOrDefault(0L, -1))
    }

    @Test
    fun `deletion should preserve a replacement value when its key becomes the successor`() {
        for (removed in 0L until 50L) {
            val index = MutableLongIntIndex()
            for (key in 0L until 50L) index.put(key, key.toInt())
            val updated = (removed + 1L) % 50L

            assertTrue(index.removeAndUpdate(removed, updated, 123, {}, {}))

            assertEquals(49, index.size)
            assertEquals(-1, index.getOrDefault(removed, -1))
            for (key in 0L until 50L) if (key != removed) {
                assertEquals(if (key == updated) 123 else key.toInt(), index.getOrDefault(key, -1))
            }
        }
    }

    @Test
    fun `cancelled funded deletion should preserve both keys and owned capacity`() {
        val index = MutableLongIntIndex()
        for (key in 0L until 64L) index.put(key, key.toInt())
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity

        assertFailsWith<IllegalStateException> {
            index.removeAndUpdate(31L, 32L, 123, {}, { error("cancelled") })
        }

        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        for (key in 0L until 64L) assertEquals(key.toInt(), index.getOrDefault(key, -1))
    }

    @Test
    fun `interrupted funding should leave deletion and replacement unpublished`() {
        val index = MutableLongIntIndex()
        for (key in 0L until 64L) index.put(key, key.toInt())
        val work = index.lastMutationWork

        assertFailsWith<IllegalStateException> {
            index.removeAndUpdate(31L, 32L, 123, { error("unfunded") }, {})
        }

        assertEquals(work, index.lastMutationWork)
        assertEquals(64, index.size)
        for (key in 0L until 64L) assertEquals(key.toInt(), index.getOrDefault(key, -1))
    }

    @Test
    fun `an absent replacement should leave deletion uncommitted`() {
        val index = MutableLongIntIndex()
        index.put(1L, 10)

        val committed = index.removeAndUpdate(1L, 2L, 20, {}, {})

        assertFalse(committed)
        assertEquals(1, index.size)
        assertEquals(10, index.getOrDefault(1L, -1))
    }

    @Test
    fun `removing a sparse majority should release ownership while retaining every survivor`() {
        val index = MutableLongIntIndex()
        for (key in 0L until 4096L) index.put(key, key.toInt())
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity

        for (key in 0L until 4000L) assertEquals(key.toInt(), index.removeAndGet(key, -1))

        assertTrue(index.primitiveCapacityBytes < bytes)
        assertTrue(index.referenceCapacity < references)
        assertEquals(96, index.size)
        for (key in 4000L until 4096L) assertEquals(key.toInt(), index.getOrDefault(key, -1))
        index.clear()
        index.put(-1L, 9)
        assertEquals(9, index.removeAndGet(-1L, 0))
        assertEquals(0, index.size)
    }
}
