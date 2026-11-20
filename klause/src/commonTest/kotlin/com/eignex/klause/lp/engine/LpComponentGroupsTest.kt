package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpComponentGroupsTest {
    @Test
    fun `cancelled grouping should leave the original coordinates usable`() {
        for (stopAt in listOf(1, 17, 25)) {
            val builder = LpBuilder()
            repeat(8) {
                val column = builder.addVar(0L, 5L)
                builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
            }
            val model = builder.build(Sense.MINIMIZE)
            val groups = LpComponentGroups(Array(8) { IntArrayList().apply { add(it) } },
                Array(8) { IntArrayList().apply { add(it) } }, 8)
            val rows = model.rowIndex()
            var polls = 0

            val cancelled = groups.coarsen(model, rows, 32L, ExecutionControl { ++polls >= stopAt },
                LpProjectionMeter())
            val retried = assertNotNull(groups.coarsen(model, rows, 32L, ExecutionControl.Never,
                LpProjectionMeter()))

            assertNull(cancelled)
            assertEquals((0 until 8).toList(), groups.columns.flatMap { it.toIntArray().toList() })
            assertEquals((0 until 8).toList(), retried.columns.flatMap { it.toIntArray().toList() })
            assertEquals((0 until 8).toList(), retried.rows.flatMap { it.toIntArray().toList() })
            assertEquals(2, retried.blocks)
        }
    }

    @Test
    fun `grouping should withhold a plan that exceeds its preparation allowance`() {
        val builder = LpBuilder()
        repeat(8) {
            val column = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val model = builder.build(Sense.MINIMIZE)
        val groups = LpComponentGroups(Array(8) { IntArrayList().apply { add(it) } },
            Array(8) { IntArrayList().apply { add(it) } }, 8)
        val rows = model.rowIndex()
        val measured = LpProjectionMeter()
        assertNotNull(groups.coarsen(model, rows, 32L, ExecutionControl.Never, measured))
        val bounded = LpProjectionMeter(workLimit = measured.work - 1L)

        assertFailsWith<LpProjectionStop> {
            groups.coarsen(model, rows, 32L, ExecutionControl.Never, bounded)
        }

        assertTrue(bounded.work > 0L)
        assertTrue(measured.allocation > 0L)
        assertEquals((0 until 8).toList(), groups.columns.flatMap { it.toIntArray().toList() })
    }
}
