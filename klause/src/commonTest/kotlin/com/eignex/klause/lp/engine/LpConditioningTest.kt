package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpConditioningTest {

    @Test
    fun `a matrix of unit coefficients needs no scaling`() {
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
    fun `coefficients spanning decades fall outside the no-scaling window`() {
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
    fun `a row mixing magnitudes is reported by the row ratio`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        // One row internally tight, one spanning six decades: only the second is row-scalable.
        b.addRow(intArrayOf(0, 1), longArrayOf(2L, 3L), Relation.LE, 10L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1_000_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1_000_000.0, spread.rowRatio)
    }

    @Test
    fun `a column mixing magnitudes is reported by the column ratio`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1L), Relation.LE, 10L)
        b.addRow(intArrayOf(0), longArrayOf(1_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        assertEquals(1_000.0, spread.columnRatio)
        assertEquals(1.0, spread.rowRatio)
    }

    @Test
    fun `slack columns are excluded so a scaled matrix is not reported as well conditioned`() {
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1_000_000L), Relation.LE, 10L)

        val spread = lpConditioning(b.build(Sense.MINIMIZE))

        // The row's slack carries a 1; counting it would report a ratio of 1e6 rather than a matrix
        // whose single structural entry is uniform.
        assertEquals(1, spread.entries)
        assertEquals(1.0, spread.matrixRatio)
        assertEquals(1_000_000.0, spread.maxValue)
        assertFalse(spread.withinHighsNoScalingWindow)
    }

    @Test
    fun `a model with no columns reports an empty spread`() {
        val spread = lpConditioning(LpBuilder().build(Sense.MINIMIZE))

        assertEquals(0, spread.entries)
        assertEquals(1.0, spread.matrixRatio)
        assertTrue(spread.withinHighsNoScalingWindow)
    }
}
