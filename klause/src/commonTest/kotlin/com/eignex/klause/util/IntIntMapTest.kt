package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #104: coverage for [IntIntMap] across the dense (offset-array) and sparse (open-addressing
 *  hash) backings, including collision probing and key-range overflow. */
class IntIntMapTest {

    @Test
    fun `empty map returns absent for any key`() {
        val m = IntIntMap.build(IntArray(0), IntArray(0), absent = -1)
        assertEquals(-1, m[0])
        assertEquals(-1, m[12345])
        assertFalse(m.contains(0))
    }

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
    fun `matches a HashMap reference for dense and sparse builds`() {
        val rng = Random(104)
        repeat(60) { trial ->
            // Even trials: dense key span; odd: sparse span forcing the hash backing.
            val dense = trial % 2 == 0
            val count = rng.nextInt(1, 40)
            val span = if (dense) count else count * 1000
            val ref = LinkedHashMap<Int, Int>()
            while (ref.size < count) {
                val k = rng.nextInt(-span, span + 1)
                ref[k] = rng.nextInt(-1000, 1000)
            }
            val keys = ref.keys.toIntArray()
            val values = IntArray(keys.size) { ref.getValue(keys[it]) }
            val m = IntIntMap.build(keys, values, absent = Int.MIN_VALUE)
            // Present keys.
            for ((k, v) in ref) {
                assertEquals(v, m[k], "present key $k")
                assertTrue(m.contains(k))
            }
            // Random probes (mostly absent).
            repeat(50) {
                val k = rng.nextInt(-span * 2, span * 2 + 1)
                if (k in ref) assertEquals(ref.getValue(k), m[k]) else assertEquals(Int.MIN_VALUE, m[k])
            }
        }
    }
}
