package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObbtBoundsTest {

    @Test
    fun `closes an open upper side a constraint bounds to the exact bound`() {
        val rows = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5))
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), rows)
        assertEquals(5L, out[0].hi) // exact certification tightens the free-column bound to the true max
    }

    @Test
    fun `leaves a side open when no constraint bounds it`() {
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList())
        assertNull(out[0].hi)
    }

    @Test
    fun `closes an open upper side through free real columns to the exact bound`() {
        // n = r1 + r2, r1 = r2, r1 + r2 <= 7 with both reals free: every row has two open real terms,
        // so interval propagation is silent and only the LP pass can bound n — exactly, not within the
        // float margin the probe-wide free columns would otherwise inflate.
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.LE, 7.0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList(), realConstraints = rows)
        assertEquals(7L, out[0].hi)
    }

    @Test
    fun `closes an open lower side through free real columns to the exact bound`() {
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.GE, -7.0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(null, 0L)), emptyList(), realConstraints = rows)
        assertEquals(-7L, out[0].lo)
    }

    @Test
    fun `descales a decimal-coefficient real bound to the exact integer floor`() {
        // r1 + r2 <= 6.6 rationalizes at the decimal scale 10; the certified bound divides back out to
        // floor(6.6) = 6, not a float-margin overestimate.
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.LE, 6.6),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList(), realConstraints = rows)
        assertEquals(6L, out[0].hi)
    }

    @Test
    fun `propagates a closed side into a later variable's bound`() {
        // y = x, x <= 4; both open above. OBBT bounds x, then y through the equality — both closed.
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null), OpenIntBounds(0L, null)), rows)
        assertEquals(4L, out[0].hi)
        assertEquals(4L, out[1].hi)
    }

    /** A row over int variable terms plus the two free reals `r1`/`r2` (ids 0 and 1). */
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
