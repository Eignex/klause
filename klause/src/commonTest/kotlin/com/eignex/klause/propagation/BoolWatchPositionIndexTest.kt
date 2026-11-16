package com.eignex.klause.propagation

import com.eignex.util.collections.IntArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoolWatchPositionIndexTest {
    @Test
    fun `paired positions should survive updates in source reserved and sparse rows`() {
        for (factor in listOf(1, 16, 4096)) {
            val index = BoolWatchPositionIndex(8)
            index.reserve(16, 8)
            index.putPair(factor, 0, 1, 2, 3)

            index.updatePair(factor, 0, 5, 2, 7)

            assertEquals(2, index.size)
            assertEquals(5, index.getOrDefault(factor.toLong() shl 32, -1))
            assertEquals(7, index.getOrDefault((factor.toLong() shl 32) or 2L, -1))
        }
    }

    @Test
    fun `paired updates should preserve other inline and overflow positions`() {
        val index = BoolWatchPositionIndex()
        index.putPair(0, 0, 1, 2, 3)
        index.put(4L, 5)

        index.updatePair(0, 0, 7, 4, 9)

        assertEquals(3, index.size)
        assertEquals(7, index.getOrDefault(0L, -1))
        assertEquals(3, index.getOrDefault(2L, -1))
        assertEquals(9, index.getOrDefault(4L, -1))
    }

    @Test
    fun `a mismatched second posting should preserve both indexed positions`() {
        val index = BoolWatchPositionIndex(1)
        index.putPair(0, 0, 0, 2, 0)
        val postings = Array(3) { IntArrayList() }
        postings[0].add(0)
        postings[2].add(1)

        assertEquals(null, index.detachInline(0, true, postings))

        assertEquals(2, index.size)
        assertEquals(0, index.getOrDefault(0L, -1))
        assertEquals(0, index.getOrDefault(2L, -1))
    }

    @Test
    fun `pressure should release unused startup rows without removing admitted positions`() {
        val index = BoolWatchPositionIndex()
        assertTrue(index.reserve(16, 8) > 0L)
        index.put(16L shl 32, 3)
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        val budget = ReclamationPreparation(4096L, 256L, { false }, {})

        index.releaseReservations(budget)

        assertTrue(index.primitiveCapacityBytes < bytes)
        assertTrue(index.referenceCapacity < references)
        assertEquals(1, index.size)
        assertEquals(3, index.getOrDefault(16L shl 32, -1))
    }

    @Test
    fun `sparse factor keys should retain independent inline and overflow positions`() {
        val index = BoolWatchPositionIndex()
        for (factor in listOf(0, 16, 4096, Int.MAX_VALUE)) {
            for (literal in 0..7) index.put((factor.toLong() shl 32) or literal.toLong(), literal + 1)
        }

        for (factor in listOf(0, 16, 4096, Int.MAX_VALUE)) {
            for (literal in 0..7) {
                assertEquals(literal + 1,
                    index.getOrDefault((factor.toLong() shl 32) or literal.toLong(), -1))
            }
        }
        assertEquals(32, index.size)
    }

    @Test
    fun `deleting high factor rows should release directory capacity beside a surviving low row`() {
        val index = BoolWatchPositionIndex()
        index.put(0L, 1)
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        index.put(Int.MAX_VALUE.toLong() shl 32, 2)
        index.put((Int.MAX_VALUE.toLong() shl 32) or 2L, 3)

        assertEquals(2, index.removeAndGet(Int.MAX_VALUE.toLong() shl 32, -1))
        assertEquals(3, index.removeAndGet((Int.MAX_VALUE.toLong() shl 32) or 2L, -1))

        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals(1, index.getOrDefault(0L, -1))
    }

    @Test
    fun `deleting every overflow position should release its owner and retain both inline positions`() {
        val index = BoolWatchPositionIndex()
        index.put(0L, 1)
        index.put(2L, 2)
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        for (literal in 4L..200L) index.put(literal, literal.toInt())

        for (literal in 4L..200L) assertEquals(literal.toInt(), index.removeAndGet(literal, -1))

        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals(1, index.getOrDefault(0L, -1))
        assertEquals(2, index.getOrDefault(2L, -1))
    }

    @Test
    fun `cancelled publication should preserve deletion replacement and capacity`() {
        val index = BoolWatchPositionIndex()
        index.put(0L, 1)
        index.put(1L shl 32, 2)
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity

        assertFailsWith<IllegalStateException> {
            index.removeAndUpdate(0L, 1L shl 32, 3, {}, { error("cancelled") })
        }

        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals(1, index.getOrDefault(0L, -1))
        assertEquals(2, index.getOrDefault(1L shl 32, -1))
    }

    @Test
    fun `funded deletion should update a second position in the same row`() {
        val index = BoolWatchPositionIndex()
        for (literal in 0L..8L) index.put(literal, literal.toInt())

        assertTrue(index.removeAndUpdate(0L, 8L, 123, {}, {}))

        assertEquals(-1, index.getOrDefault(0L, -1))
        assertEquals(123, index.getOrDefault(8L, -1))
        assertEquals(8, index.size)
    }

    @Test
    fun `a missing replacement should preserve the removed position`() {
        val index = BoolWatchPositionIndex()
        index.put(0L, 1)

        assertFalse(index.removeAndUpdate(0L, 2L, 3, {}, {}))

        assertEquals(1, index.getOrDefault(0L, -1))
        assertEquals(1, index.size)
        assertEquals(3, index.removeAndGet(2L, 3))
    }
}
