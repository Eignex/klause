package com.eignex.klause.factor.table.internals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableSupportIndexTest {
    @Test
    fun `mixed sparse and dense supports should visit exactly the requested rows`() {
        val tuples = LongArray(256 * 2) { cell ->
            val row = cell / 2
            if (cell % 2 == 0) row.toLong() else if (row < 128) -1 else row.toLong()
        }
        val index = assertNotNull(TableSupportIndex.build(tuples, 2))

        for (col in 0..1) {
            for ((lower, upper) in listOf(-1L to -1L, 127L to 132L, -1L to 255L, 300L to 400L)) {
                val rows = ArrayList<Int>()
                index.columns[col].forEachRow(lower, upper, rows::add)

                assertEquals((0 until 256).filter { tuples[it * 2 + col] in lower..upper }, rows.sorted())
            }
        }
        assertTrue(index.retainedBytes < tuples.size * 16L)
    }

    @Test
    fun `support bounds should preserve both signed endpoints`() {
        val tuples = longArrayOf(Long.MAX_VALUE, 0, Long.MIN_VALUE, Long.MAX_VALUE)
        val index = assertNotNull(TableSupportIndex.build(tuples, 1))

        for (value in listOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)) {
            val rows = ArrayList<Int>()
            index.columns[0].forEachRow(value, value, rows::add)

            assertEquals(tuples.indices.filter { tuples[it] == value }, rows.sorted())
        }
    }

    @Test
    fun `insufficient storage should decline the complete index`() {
        val tuples = LongArray(128) { it.toLong() }

        val index = TableSupportIndex.build(tuples, 2, maxBytes = 1024)

        assertNull(index)
    }

    @Test
    fun `empty supports should visit no rows`() {
        val index = assertNotNull(TableSupportIndex.build(longArrayOf(), 2))
        val rows = ArrayList<Int>()

        index.columns[0].forEachRow(Long.MIN_VALUE, Long.MAX_VALUE, rows::add)

        assertTrue(rows.isEmpty())
    }
}
