package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearLpGraphTest {
    @Test
    fun `shared columns should connect rows transitively`() {
        val rows = listOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(1, 2))

        val result = LinearLpGraph.partition(4, rows, BooleanArray(3) { true })

        assertEquals(LinearLpPartition(listOf(LinearLpBlock(4, 3)), 0, 0), result)
    }

    @Test
    fun `excluded bridge should leave independent row coupled blocks`() {
        val rows = listOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(1, 2))

        val result = LinearLpGraph.partition(4, rows, booleanArrayOf(true, true, false))

        assertEquals(LinearLpPartition(listOf(LinearLpBlock(2, 1), LinearLpBlock(2, 1)), 0, 0), result)
    }

    @Test
    fun `isolated columns and included empty rows should be counted separately`() {
        val rows = listOf(intArrayOf(1), intArrayOf(), intArrayOf())

        val result = LinearLpGraph.partition(3, rows, booleanArrayOf(true, true, false))

        assertEquals(LinearLpPartition(listOf(LinearLpBlock(1, 1)), 2, 1), result)
    }

    @Test
    fun `cancellation within a wide row should return no partial partition`() {
        var polls = 0
        val rows = listOf(IntArray(4096) { it })

        val result = LinearLpGraph.partition(4096, rows, booleanArrayOf(true), Cancellation { ++polls == 2 })

        assertNull(result)
    }
}
