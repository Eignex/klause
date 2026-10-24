package com.eignex.klause.factor.table.internals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TableGroupCacheTest {
    @Test
    fun `interrupted support construction should publish no partial shared index`() {
        val cache = TableGroupCache(2, LongArray(256) { (it / 2).toLong() }, null)
        var visits = 0
        assertFailsWith<IllegalStateException> {
            cache.supports { if (++visits == 4) error("stopped construction") }
        }

        val index = assertNotNull(cache.supports {})
        val rows = ArrayList<Int>()
        index.columns[1].forEachRow(64, 64, rows::add)

        assertEquals(listOf(64), rows)
    }
}
