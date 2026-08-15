package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.solver.Factor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Closing an open side the objective makes pointless. The risk runs one way — a side closed that the
 * model actually needs open would cut off the optimum — so every disqualifying shape is pinned.
 */
class DualBoundFixTest {

    private fun openAbove(lo: Long) = OpenIntBounds(lo, null)
    private fun openBelow(hi: Long) = OpenIntBounds(null, hi)

    private fun fix(bounds: Array<OpenIntBounds>, cost: LongArray, vararg f: Factor) =
        dualFixableBounds(bounds.size, f.toList(), bounds) { cost[it] }

    @Test
    fun `a rising cost never needed larger pins the column at its lower bound`() {
        val r = fix(
            arrayOf(openAbove(0L)),
            longArrayOf(5L),
            Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 10L),
        )
        assertEquals(0L, r[0].hi, "walking it down is free and stays feasible")
    }

    @Test
    fun `a column a constraint may need larger stays open`() {
        // A negative coefficient in a canonical <= row is a >= row: raising the column helps feasibility.
        val r = fix(
            arrayOf(openAbove(0L)),
            longArrayOf(5L),
            Linear(longArrayOf(-1L), intArrayOf(0), LinearOp.LE, -3L),
        )
        assertNull(r[0].hi)
    }

    @Test
    fun `an equality pins the column so it is left alone`() {
        val r = fix(
            arrayOf(openAbove(0L)),
            longArrayOf(5L),
            Linear(longArrayOf(1L), intArrayOf(0), LinearOp.EQ, 7L),
        )
        assertNull(r[0].hi)
    }

    @Test
    fun `a reified row only constrains on one branch so it disqualifies`() {
        val r = fix(
            arrayOf(openAbove(0L)),
            longArrayOf(5L),
            ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, 10L),
        )
        assertNull(r[0].hi)
    }

    @Test
    fun `a costless column is left alone`() {
        val r = fix(
            arrayOf(openAbove(0L)),
            longArrayOf(0L),
            Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 10L),
        )
        assertNull(r[0].hi, "no cost gradient means no reason to prefer either end")
    }

    @Test
    fun `a falling cost never needed smaller pins the column at its upper bound`() {
        val r = fix(
            arrayOf(openBelow(9L)),
            longArrayOf(-5L),
            Linear(longArrayOf(-1L), intArrayOf(0), LinearOp.LE, 4L),
        )
        assertEquals(9L, r[0].lo, "walking it up is free and stays feasible")
    }

    @Test
    fun `a bounded column is returned untouched`() {
        val r = fix(
            arrayOf(OpenIntBounds(0L, 4L)),
            longArrayOf(5L),
            Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 10L),
        )
        assertEquals(0L, r[0].lo)
        assertEquals(4L, r[0].hi)
    }
}
