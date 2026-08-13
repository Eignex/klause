package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Row-direction boundedness, the classification the double-bounded reduction discards rows by. A row is
 * bounded when the system pins its linear form from both sides; open variables are the interesting case,
 * since a bounded row over unbounded variables is exactly what the reduction has to keep.
 */
class DoubleBoundedReductionTest {

    private fun open(n: Int) = Array(n) { OpenIntBounds(null, null) }

    @Test
    fun `a row bounded on both sides by the system is kept`() {
        // 1 <= x0 - x1 <= 2 over two unbounded variables: both rows bound the direction x0 - x1, which
        // the system pins from both sides even though neither variable is bounded.
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1),
        )
        assertContentEquals(booleanArrayOf(true, true), boundedRowMask(open(2), rows))
    }

    @Test
    fun `a row open on one side is dropped`() {
        // x0 + x1 <= 5 alone leaves the direction unbounded below, so the row carries no two-sided bound.
        val rows = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        assertContentEquals(booleanArrayOf(false), boundedRowMask(open(2), rows))
    }

    @Test
    fun `a row over variables with declared bounds is kept`() {
        val rows = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        val bounds = arrayOf(OpenIntBounds(0L, 10L), OpenIntBounds(0L, 10L))
        assertContentEquals(booleanArrayOf(true), boundedRowMask(bounds, rows))
    }

    @Test
    fun `an equality is bounded on both sides by construction`() {
        val rows = listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0))
        assertContentEquals(booleanArrayOf(true), boundedRowMask(open(2), rows))
    }

    @Test
    fun `classification is empty for an empty system`() {
        assertTrue(boundedRowMask(open(2), emptyList()).isEmpty())
    }
}
