package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #104: direct coverage for [IntArrayList], including its O(1) swap-remove semantics. */
class IntArrayListTest {

    @Test
    fun `add grows past initial capacity and preserves order`() {
        val list = IntArrayList(initialCapacity = 2)
        for (i in 0 until 100) list.add(i * 3)
        assertEquals(100, list.size)
        for (i in 0 until 100) assertEquals(i * 3, list[i])
        assertEquals(IntArray(100) { it * 3 }.toList(), list.toIntArray().toList())
    }

    @Test
    fun `removeAt swap-removes the tail into the gap`() {
        val list = IntArrayList()
        for (v in intArrayOf(10, 20, 30, 40)) list.add(v)
        list.removeAt(1) // 20 replaced by tail 40
        assertEquals(3, list.size)
        assertEquals(listOf(10, 40, 30), list.toIntArray().toList())
    }

    @Test
    fun `removeValue removes first occurrence by swap and reports presence`() {
        val list = IntArrayList()
        for (v in intArrayOf(5, 7, 5, 9)) list.add(v)
        assertTrue(list.removeValue(5))
        assertEquals(3, list.size)
        // First 5 (index 0) replaced by tail 9.
        assertEquals(listOf(9, 7, 5), list.toIntArray().toList())
        assertFalse(list.removeValue(42), "removing an absent value returns false")
        assertEquals(3, list.size)
    }

    @Test
    fun `truncateTo drops the suffix and is a no-op when not shrinking`() {
        val list = IntArrayList()
        for (v in 0 until 6) list.add(v)
        list.truncateTo(3)
        assertEquals(listOf(0, 1, 2), list.toIntArray().toList())
        list.truncateTo(10) // no-op
        assertEquals(3, list.size)
    }

    @Test
    fun `indexOf contains last and toSet`() {
        val list = IntArrayList()
        for (v in intArrayOf(4, 4, 8)) list.add(v)
        assertEquals(0, list.indexOf(4))
        assertEquals(-1, list.indexOf(99))
        assertTrue(list.contains(8))
        assertFalse(list.contains(99))
        assertEquals(8, list.last())
        assertEquals(setOf(4, 8), list.toSet())
    }

    @Test
    fun `clear and isEmpty`() {
        val list = IntArrayList()
        assertTrue(list.isEmpty())
        list.add(1)
        assertFalse(list.isEmpty())
        list.clear()
        assertTrue(list.isEmpty())
        assertEquals(0, list.size)
    }

    @Test
    fun `sortByIntKey orders by key ascending and descending with element tie-break`() {
        val list = IntArrayList()
        for (v in intArrayOf(3, 1, 2, 5, 4)) list.add(v)
        list.sortByIntKey { it } // identity key → plain value sort
        assertEquals(listOf(1, 2, 3, 4, 5), list.toIntArray().toList())
        list.sortByIntKey(descending = true) { it }
        assertEquals(listOf(5, 4, 3, 2, 1), list.toIntArray().toList())
    }

    @Test
    fun `sortByIntKey sorts by an external key and is stable on ties via element order`() {
        // Elements are item ids; key is a weight lookup with deliberate ties.
        val weights = intArrayOf(10, 5, 10, 5, 7) // by id
        val list = IntArrayList()
        for (id in 0 until 5) list.add(id)
        list.sortByIntKey { weights[it] }
        // Ascending weight: {1,3}=5, {4}=7, {0,2}=10; ties break by id ascending.
        assertEquals(listOf(1, 3, 4, 0, 2), list.toIntArray().toList())
        list.sortByIntKey(descending = true) { weights[it] }
        // Descending is the exact reverse of the ascending packed order.
        assertEquals(listOf(2, 0, 4, 3, 1), list.toIntArray().toList())
    }

    @Test
    fun `sortByIntKey handles negative keys and elements`() {
        val list = IntArrayList()
        for (v in intArrayOf(-3, 7, 0, -10, 4)) list.add(v)
        list.sortByIntKey { it }
        assertEquals(listOf(-10, -3, 0, 4, 7), list.toIntArray().toList())
    }

    @Test
    fun `sortByIntKey matches a stdlib reference under random keys`() {
        val rng = Random(7)
        repeat(20) { _ ->
            val n = rng.nextInt(0, 40)
            val list = IntArrayList()
            val ref = ArrayList<Int>()
            repeat(n) {
                val v = rng.nextInt(-50, 50)
                list.add(v)
                ref.add(v)
            }
            val desc = rng.nextBoolean()
            val key: (Int) -> Int = { it / 3 } // many ties
            list.sortByIntKey(descending = desc) { key(it) }
            // Reference: stable sort by key, with the same (key high, element low) packing,
            // which orders ties by element value — reverse for descending.
            val expected = ref.map { (key(it).toLong() shl 32) or (it.toLong() and 0xFFFFFFFFL) }
                .sorted()
                .map { (it and 0xFFFFFFFFL).toInt() }
                .let { if (desc) it.reversed() else it }
            assertEquals(expected, list.toIntArray().toList())
        }
    }

    @Test
    fun `random ops match a swap-remove reference model`() {
        val rng = Random(104)
        repeat(15) {
            val list = IntArrayList(initialCapacity = 1)
            val ref = ArrayList<Int>() // mirrors the same swap-remove semantics
            repeat(300) {
                when (rng.nextInt(4)) {
                    0 -> {
                        val v = rng.nextInt(20)
                        list.add(v)
                        ref.add(v)
                    }

                    1 -> if (ref.isNotEmpty()) {
                        val i = rng.nextInt(ref.size)
                        list.removeAt(i)
                        ref[i] = ref[ref.size - 1]
                        ref.removeAt(ref.size - 1)
                    }

                    2 -> {
                        val v = rng.nextInt(20)
                        val found = list.removeValue(v)
                        val idx = ref.indexOf(v)
                        assertEquals(idx >= 0, found, "removeValue presence must match reference")
                        if (idx >= 0) {
                            ref[idx] = ref[ref.size - 1]
                            ref.removeAt(ref.size - 1)
                        }
                    }

                    3 -> if (ref.isNotEmpty()) {
                        val i = rng.nextInt(ref.size)
                        val v = rng.nextInt(20)
                        list[i] = v
                        ref[i] = v
                    }
                }
                assertEquals(ref.size, list.size)
            }
            assertEquals(ref, list.toIntArray().toList())
        }
    }
}
