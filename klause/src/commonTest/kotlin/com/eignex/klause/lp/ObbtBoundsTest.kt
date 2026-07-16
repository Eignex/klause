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
}
