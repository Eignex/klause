package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpConditioningTest {

    @Test
    fun `a matrix of unit coefficients should need no scaling`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1.0, spread.minValue)
        assertEquals(1.0, spread.maxValue)
        assertEquals(1.0, spread.matrixRatio)
        assertTrue(spread.withinHighsNoScalingWindow)
    }

    @Test
    fun `coefficients spanning decades should fall outside the no-scaling window`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1_000_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1.0, spread.minValue)
        assertEquals(1_000_000.0, spread.maxValue)
        assertEquals(1_000_000.0, spread.matrixRatio)
        assertFalse(spread.withinHighsNoScalingWindow)
    }

    @Test
    fun `a row mixing magnitudes should be reported by the row ratio`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(2L, 3L), Relation.LE, 10L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1_000_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1_000_000.0, spread.rowRatio)
    }

    @Test
    fun `conditioning should exclude slack columns from the matrix ratio`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1_000_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1, spread.entries)
        assertEquals(1.0, spread.matrixRatio)
        assertEquals(1_000_000.0, spread.maxValue)
        assertFalse(spread.withinHighsNoScalingWindow)
    }
}
