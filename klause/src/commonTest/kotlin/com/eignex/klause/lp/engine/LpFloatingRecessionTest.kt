package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpFloatingRecessionTest {
    @Test
    fun `a recession should follow the logical bound of each row relation`() {
        for ((relation, accepted) in listOf(Relation.LE to false, Relation.EQ to false, Relation.GE to true)) {
            val builder = LpBuilder()
            val x = builder.addOpenAboveVar(0L, cost = -1L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), relation, 0L)

            val checked = floatingRecession(builder.build(Sense.MINIMIZE), doubleArrayOf(1.0), 1000L)

            assertEquals(accepted, checked.accepted)
            assertTrue(checked.work > 0L)
        }
    }

    @Test
    fun `recession validation should refuse finite bounds and nonimproving directions`() {
        for ((upper, direction) in listOf(1L to 1.0, null to -1.0, null to 0.0, null to Double.NaN)) {
            val builder = LpBuilder()
            if (upper == null) builder.addOpenAboveVar(0L, cost = -1L) else builder.addVar(0L, upper, cost = -1L)

            val checked = floatingRecession(builder.build(Sense.MINIMIZE), doubleArrayOf(direction), 1000L)

            assertFalse(checked.accepted)
        }
    }

    @Test
    fun `recession validation should stop within its remaining work`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        for (work in 0L..2L) {
            val checked = floatingRecession(model, doubleArrayOf(1.0), work)

            assertFalse(checked.accepted)
            assertEquals(LpFloatingBoundStop.WORK, checked.stop)
            assertTrue(checked.work <= work)
        }
    }

    @Test
    fun `cancellation should withhold a recession after paid inspection`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        var polls = 0

        val checked = floatingRecession(model, doubleArrayOf(1.0), 1000L, Cancellation { ++polls >= 3 })

        assertFalse(checked.accepted)
        assertEquals(LpFloatingBoundStop.CANCELLED, checked.stop)
        assertTrue(checked.work > 0L)
    }
}
