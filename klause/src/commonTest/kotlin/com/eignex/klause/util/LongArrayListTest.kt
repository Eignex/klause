package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [LongArrayList] — the `Long` sibling of [IntArrayList]; mirrors its semantics
 *  (growth, O(1) swap-remove, truncate) plus the stdlib-backed [LongArrayList.sort]. */
class LongArrayListTest {

    @Test
    fun `add grows past initial capacity and preserves order`() {
        val list = LongArrayList(initialCapacity = 2)
        for (i in 0 until 100) list.add(i * 3_000_000_000L) // values beyond Int range
        assertEquals(100, list.size)
        for (i in 0 until 100) assertEquals(i * 3_000_000_000L, list[i])
    }

    @Test
    fun `removeAt swap-removes the tail into the gap`() {
        val list = LongArrayList()
        for (v in longArrayOf(10, 20, 30, 40)) list.add(v)
        list.removeAt(1)
        assertEquals(3, list.size)
        assertEquals(listOf(10L, 40L, 30L), list.toLongArray().toList())
    }

    @Test
    fun `removeValue removes first occurrence and reports presence`() {
        val list = LongArrayList()
        for (v in longArrayOf(5, 7, 5, 9)) list.add(v)
        assertTrue(list.removeValue(5))
        assertEquals(3, list.size)
        assertEquals(listOf(9L, 7L, 5L), list.toLongArray().toList())
        assertFalse(list.removeValue(42))
    }

    @Test
    fun `insertAt shifts the tail right`() {
        val list = LongArrayList()
        for (v in longArrayOf(1, 2, 3)) list.add(v)
        list.insertAt(1, 99)
        assertEquals(listOf(1L, 99L, 2L, 3L), list.toLongArray().toList())
    }

    @Test
    fun `truncateTo drops the suffix and is a no-op when not shrinking`() {
        val list = LongArrayList()
        for (v in 0L until 6L) list.add(v)
        list.truncateTo(3)
        assertEquals(listOf(0L, 1L, 2L), list.toLongArray().toList())
        list.truncateTo(10)
        assertEquals(3, list.size)
    }

    @Test
    fun `indexOf contains last toSet and clear`() {
        val list = LongArrayList()
        for (v in longArrayOf(4, 4, 8)) list.add(v)
        assertEquals(0, list.indexOf(4))
        assertEquals(-1, list.indexOf(99))
        assertTrue(list.contains(8))
        assertEquals(8L, list.last())
        assertEquals(setOf(4L, 8L), list.toSet())
        list.clear()
        assertTrue(list.isEmpty())
    }

    @Test
    fun `sort orders ascending and descending including out-of-int-range values`() {
        val list = LongArrayList()
        for (v in longArrayOf(3, -10_000_000_000L, 0, 9_000_000_000L, -3)) list.add(v)
        list.sort()
        assertEquals(listOf(-10_000_000_000L, -3L, 0L, 3L, 9_000_000_000L), list.toLongArray().toList())
        list.sort(descending = true)
        assertEquals(listOf(9_000_000_000L, 3L, 0L, -3L, -10_000_000_000L), list.toLongArray().toList())
    }

    @Test
    fun `forEach visits every element in order`() {
        val list = LongArrayList()
        for (v in longArrayOf(2, 4, 6)) list.add(v)
        val seen = ArrayList<Long>()
        list.forEach { seen.add(it) }
        assertEquals(listOf(2L, 4L, 6L), seen)
    }

    @Test
    fun `random add-remove ops match a swap-remove reference model`() {
        val rng = Random(99)
        repeat(15) {
            val list = LongArrayList(initialCapacity = 1)
            val ref = ArrayList<Long>()
            repeat(300) {
                when (rng.nextInt(3)) {
                    0 -> {
                        val v = rng.nextLong(-20, 20)
                        list.add(v)
                        ref.add(v)
                    }

                    1 -> if (ref.isNotEmpty()) {
                        val i = rng.nextInt(ref.size)
                        list.removeAt(i)
                        ref[i] = ref[ref.size - 1]
                        ref.removeAt(ref.size - 1)
                    }

                    2 -> if (ref.isNotEmpty()) {
                        val i = rng.nextInt(ref.size)
                        val v = rng.nextLong(-20, 20)
                        list[i] = v
                        ref[i] = v
                    }
                }
                assertEquals(ref.size, list.size)
            }
            assertEquals(ref, list.toLongArray().toList())
        }
    }
}
