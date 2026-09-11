package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ArgSortTest {
    @Test
    fun `caller buffers retain stable ordering across changing logical lengths`() {
        val order = IntArray(9) { -1 }
        val scratch = IntArray(9) { -1 }
        val keys = doubleArrayOf(2.0, -0.0, 2.0, 0.0, -3.0, Double.NaN, 2.0, -3.0, 1.0)

        for (size in listOf(9, 0, 1, 4, 2, 7, 9)) {
            val actual = argsortBy(size, order, scratch) { a, b -> keys[a].compareTo(keys[b]) }

            val expected = (0 until size).sortedWith(compareBy { keys[it] }).toIntArray()
            assertContentEquals(expected, actual.copyOf(size))
        }
    }

    @Test
    fun `invalid logical lengths preserve caller buffers`() {
        for (size in listOf(-1, 4)) {
            val order = intArrayOf(7, 8, 9)
            val scratch = intArrayOf(4, 5, 6)

            assertFailsWith<IllegalArgumentException> { argsortBy(size, order, scratch) { a, b -> a - b } }

            assertContentEquals(intArrayOf(7, 8, 9), order)
            assertContentEquals(intArrayOf(4, 5, 6), scratch)
        }
    }

    @Test
    fun `aliased or insufficient scratch is rejected before mutation`() {
        val order = intArrayOf(7, 8, 9)
        for (scratch in listOf(order, intArrayOf(4, 5))) {
            assertFailsWith<IllegalArgumentException> { argsortBy(3, order, scratch) { a, b -> a - b } }

            assertContentEquals(intArrayOf(7, 8, 9), order)
        }
    }
}
