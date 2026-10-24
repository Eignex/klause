package com.eignex.klause.factor.table.internals

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TableLsStateTest {
    @Test
    fun `distance buckets should preserve minimum and row order through edits and reversals`() {
        val distances = intArrayOf(3, 1, 2, 1, 0, 3, 2)
        val state = TableLsState(distances.copyOf(), 3)
        val random = Random(5)
        repeat(100) {
            val row = random.nextInt(distances.size)
            val before = distances[row]
            val after = random.nextInt(4)
            for (distance in listOf(after, before, after)) {
                state.setDistance(row, distance)
                distances[row] = distance
                val expected = distances.indices.sortedWith(compareBy({ distances[it] }, { it })).take(2).toIntArray()
                assertContentEquals(expected, state.closestRows())
                assertEquals(distances.min(), state.minDist)
            }
        }
    }
}
