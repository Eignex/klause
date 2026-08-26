package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObbtBoundsTest {

    @Test
    fun `closes an open upper side a constraint bounds to the exact bound`() {
        val rows = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5))
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), rows)
        assertEquals(5L, out.bounds[0].hi) // exact certification tightens the free-column bound to the true max
    }

    @Test
    fun `leaves a side open when no constraint bounds it`() {
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), emptyList())
        assertNull(out.bounds[0].hi)
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
        assertEquals(7L, out.bounds[0].hi)
    }

    @Test
    fun `closes an open lower side through free real columns to the exact bound`() {
        val rows = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.GE, -7.0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(null, 0L)), emptyList(), realConstraints = rows)
        assertEquals(-7L, out.bounds[0].lo)
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
        assertEquals(6L, out.bounds[0].hi)
    }

    @Test
    fun `propagates a closed side into a later variable's bound`() {
        // y = x, x <= 4; both open above. OBBT bounds x, then y through the equality — both closed.
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 0),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null), OpenIntBounds(0L, null)), rows)
        assertEquals(4L, out.bounds[0].hi)
        assertEquals(4L, out.bounds[1].hi)
    }

    @Test
    fun `bounds a side on an oversized model through its neighborhood probe`() {
        // The three real rows bound n only through the LP (interval propagation is silent, as above);
        // 5001 padding rows on an unrelated variable push the model past the full-LP row cap, so the
        // pass switches to neighborhood probes (#1425) — n's neighborhood is exactly the three real
        // rows, so its bound still closes instead of falling to the clamp.
        val lpOnly = listOf(
            realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(-1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
            realRow(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, 1.0), LinearOp.LE, 7.0),
        )
        val padding = List(5001) { i -> Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 1_000 + i) }
        val bounds = arrayOf(OpenIntBounds(0L, null), OpenIntBounds(0L, 10L))
        val out = tightenOpenIntBounds(bounds, padding, realConstraints = lpOnly)
        assertEquals(7L, out.bounds[0].hi, "the neighborhood probe closes the locally derivable bound")
    }

    @Test
    fun `refutes a system whose rows cross a variable's own bounds`() {
        // x <= 3 and x >= 5.
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
        )
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), rows)
        assertTrue(out.refuted)
    }

    @Test
    fun `a crossing reached through a real row bounds without refuting`() {
        // x <= -1 through the outward-rounded real arithmetic, whose margin may bound but not assert unsat.
        val rows = listOf(realRow(longArrayOf(1), intArrayOf(0), doubleArrayOf(0.0, 0.0), LinearOp.LE, -1.0))
        val bounds = arrayOf(OpenIntBounds(0L, null), OpenIntBounds(0L, null))
        val out = tightenOpenIntBounds(bounds, emptyList(), realConstraints = rows)
        assertFalse(out.refuted)
        assertEquals(0L, out.bounds[0].hi, "collapsed to where the sides met, never handed over crossed")
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
