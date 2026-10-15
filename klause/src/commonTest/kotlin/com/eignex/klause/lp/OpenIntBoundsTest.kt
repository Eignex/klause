package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenIntBoundsTest {

    @Test
    fun `obbt should leave a side open when no constraint bounds it`() {
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList())
        assertNull(out.bounds[0].hi)
    }

    @Test
    fun `obbt should close an upper side through free reals to the exact bound`() {
        // n = r1 + r2, r1 = r2, r1 + r2 <= 7: every row has two open real terms, so only the LP bounds n.
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.LE, 7.0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList(), realConstraints = rows)
        assertEquals(7L, out.bounds[0].hi)
    }

    @Test
    fun `obbt should descale a decimal real bound to the exact integer floor`() {
        // r1 + r2 <= 6.6 rationalizes at scale 10 and divides back out to floor(6.6) = 6.
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.LE, 6.6),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList(), realConstraints = rows)
        assertEquals(6L, out.bounds[0].hi)
    }

    @Test
    fun `obbt should propagate a closed side into a later variable bound`() {
        // y = x, x <= 4; both open above.
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null), OpenIntBounds(0L, null)), rows)
        assertEquals(4L, out.bounds[0].hi)
        assertEquals(4L, out.bounds[1].hi)
    }

    @Test
    fun `obbt should refute rows that cross a variable bounds`() {
        // x <= 3 and x >= 5.
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), rows)
        assertTrue(out.refuted)
    }

    private fun realRow(
        intCoeffs: LongArray,
        intVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
    ): Linear = Linear(
        intVars,
        DoubleArray(intCoeffs.size) { intCoeffs[it].toDouble() },
        intArrayOf(0, 1),
        realCoeffs,
        op,
        bound,
    )
}
