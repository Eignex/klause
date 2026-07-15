package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObbtBoundsTest {

    @Test
    fun `closes an open upper side a constraint bounds`() {
        val rows = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5))
        val out = tightenOpenIntBounds(arrayOf(OpenIntBounds(0L, null)), rows)
        // A sound bound the caller can commit as a finite domain (loose is fine — never below the true max 5).
        val hi = assertNotNull(out[0].hi)
        assertTrue(hi >= 5L, "unsound upper bound $hi below the true max 5")
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
        val xHi = assertNotNull(out[0].hi)
        val yHi = assertNotNull(out[1].hi)
        assertTrue(xHi >= 4L && yHi >= 4L, "unsound bounds x<=$xHi y<=$yHi below the true max 4")
    }
}
