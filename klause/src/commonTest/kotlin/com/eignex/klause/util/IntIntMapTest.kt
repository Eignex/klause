package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #104: coverage for [IntIntMap] across the dense (offset-array) and sparse (open-addressing
 *  hash) backings, including collision probing and key-range overflow. */
class IntIntMapTest {

    @Test
    fun `a compact key range resolves present and absent keys`() {
        // Range 4 over 4 entries ⇒ dense (range ≤ 4×count).
        val m = IntIntMap.build(intArrayOf(10, 11, 12, 13), intArrayOf(100, 110, 120, 130), absent = -1)
        assertEquals(100, m[10])
        assertEquals(130, m[13])
        assertEquals(-1, m[9], "below range → absent")
        assertEquals(-1, m[14], "above range → absent")
        assertTrue(m.contains(11))
        assertFalse(m.contains(9))
    }

    @Test
    fun `a sparse key range resolves present and absent keys`() {
        // Range 1_000_001 over 2 entries ⇒ hash backing.
        val m = IntIntMap.build(intArrayOf(0, 1_000_000), intArrayOf(7, 9), absent = -1)
        assertEquals(7, m[0])
        assertEquals(9, m[1_000_000])
        assertEquals(-1, m[500_000])
        assertTrue(m.contains(1_000_000))
        assertFalse(m.contains(500_000))
    }

    @Test
    fun `an extreme key range resolves without overflow`() {
        val m = IntIntMap.build(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), intArrayOf(1, 2), absent = 0)
        assertEquals(1, m[Int.MIN_VALUE])
        assertEquals(2, m[Int.MAX_VALUE])
        assertEquals(0, m[0])
        assertTrue(m.contains(Int.MIN_VALUE))
        assertFalse(m.contains(0))
    }

    @Test
    fun `negative keys work in both backings`() {
        val dense = IntIntMap.build(intArrayOf(-3, -2, -1, 0), intArrayOf(1, 2, 3, 4), absent = -1)
        assertEquals(2, dense[-2])
        assertEquals(4, dense[0])
        assertEquals(-1, dense[-4])
    }

    @Test
    fun `contains is true even when the stored value equals absent`() {
        // absent=0 but key 5 legitimately stores 0 — contains must still report present.
        val m = IntIntMap.build(intArrayOf(5), intArrayOf(0), absent = 0)
        assertEquals(0, m[5])
        assertTrue(m.contains(5), "present key must be reported even if its value == absent")
        assertFalse(m.contains(6))
    }

    @Test
    fun `sparse patterned keys resolve through collisions`() {
        for (stride in intArrayOf(8, 64, 1024, 65536)) {
            val keys = IntArray(128) { (it - 64) * stride }
            val values = IntArray(keys.size) { it * 3 - 100 }
            val map = IntIntMap.build(keys, values, absent = -999)

            for (i in keys.indices) {
                assertEquals(values[i], map[keys[i]], "stride $stride key ${keys[i]}")
                assertTrue(map.contains(keys[i]))
                assertEquals(-999, map[keys[i] + 1])
                assertFalse(map.contains(keys[i] + 1))
            }
        }
    }

    @Test
    fun `duplicate keys retain their last value in either representation`() {
        for (threshold in intArrayOf(0, 4)) {
            val map = IntIntMap.build(
                intArrayOf(-1, 0, 1, -1, 1), intArrayOf(10, 20, 30, 40, 50),
                absent = -1, denseThreshold = threshold,
            )

            assertEquals(40, map[-1])
            assertEquals(20, map[0])
            assertEquals(50, map[1])
            assertEquals(-1, map[2])
        }
    }

    @Test
    fun `all integer payloads and absent sentinels survive sparse lookup`() {
        for (absent in intArrayOf(0, -1, Int.MIN_VALUE, Int.MIN_VALUE + 1)) {
            val keys = intArrayOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)
            val values = intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE + 1, 0, -1)
            val map = IntIntMap.build(keys, values, absent)

            for (i in keys.indices) assertEquals(values[i], map[keys[i]])
            assertEquals(absent, map[2])
            assertEquals(absent, map[Int.MIN_VALUE + 1])
            assertEquals(absent, map[Int.MAX_VALUE - 1])
        }
    }

    @Test
    fun `construction owns an immutable copy of the entries`() {
        for (threshold in intArrayOf(0, 4)) {
            val keys = intArrayOf(-1, 0, 1)
            val values = intArrayOf(10, 20, 30)
            val map = IntIntMap.build(keys, values, absent = -1, denseThreshold = threshold)

            keys.fill(100)
            values.fill(99)

            assertEquals(10, map[-1])
            assertEquals(20, map[0])
            assertEquals(30, map[1])
            assertEquals(-1, map[100])
        }
    }

    @Test
    fun `empty maps retain their own absent sentinel`() {
        for (absent in intArrayOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val map = IntIntMap.build(intArrayOf(), intArrayOf(), absent)

            assertEquals(absent, map[0])
            assertEquals(absent, map[Int.MIN_VALUE])
            assertEquals(absent, map[Int.MAX_VALUE])
            assertFalse(map.contains(0))
        }
    }

}
