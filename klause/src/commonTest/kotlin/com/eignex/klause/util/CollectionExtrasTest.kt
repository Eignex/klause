package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Coverage for the shared collection affordances in `CollectionExtras.kt`. */
class CollectionExtrasTest {

    @Test
    fun `toSortedIntArray returns an ascending snapshot`() {
        val list = IntArrayList().apply {
            add(3)
            add(1)
            add(2)
            add(1)
        }
        assertContentEquals(intArrayOf(1, 1, 2, 3), list.toSortedIntArray())
        val set = IntHashSet().apply {
            add(9)
            add(-4)
            add(0)
        }
        assertContentEquals(intArrayOf(-4, 0, 9), set.toSortedIntArray())
    }

    @Test
    fun `toSortedLongArray returns an ascending snapshot`() {
        val list = LongArrayList().apply {
            add(3L)
            add(1L)
        }
        assertContentEquals(longArrayOf(1L, 3L), list.toSortedLongArray())
        val set = LongHashSet().apply {
            add(9L)
            add(-4L)
        }
        assertContentEquals(longArrayOf(-4L, 9L), set.toSortedLongArray())
    }

    @Test
    fun `sortedKeys returns the keys ascending for every map flavour`() {
        val ii = MutableIntIntMap().apply {
            put(5, 0)
            put(-1, 0)
            put(3, 0)
        }
        assertContentEquals(intArrayOf(-1, 3, 5), ii.sortedKeys())
        val il = MutableIntLongMap().apply {
            put(5, 0L)
            put(-1, 0L)
        }
        assertContentEquals(intArrayOf(-1, 5), il.sortedKeys())
        val id = MutableIntDoubleMap().apply {
            put(5, 0.0)
            put(-1, 0.0)
        }
        assertContentEquals(intArrayOf(-1, 5), id.sortedKeys())
        val io = MutableIntObjectMap<String>().apply {
            put(5, "a")
            put(-1, "b")
        }
        assertContentEquals(intArrayOf(-1, 5), io.sortedKeys())
    }
}
