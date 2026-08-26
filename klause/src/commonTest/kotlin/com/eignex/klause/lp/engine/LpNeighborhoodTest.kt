package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.columnNeighborhood
import com.eignex.klause.lp.engine.rowIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpNeighborhoodTest {

    // Chain x0 <= x1 <= x2 <= 5: three rows, each linking to the next variable.
    private fun chain(): Pair<LpModel, IntArray> {
        val b = LpBuilder()
        val x0 = b.addVar(0L, 100L)
        val x1 = b.addVar(0L, 100L)
        val x2 = b.addVar(0L, 100L)
        b.addRow(intArrayOf(x0, x1), longArrayOf(1L, -1L), Relation.LE, 0L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, -1L), Relation.LE, 0L)
        b.addRow(intArrayOf(x2), longArrayOf(1L), Relation.LE, 5L)
        return b.build(Sense.MINIMIZE) to intArrayOf(x0, x1, x2)
    }

    @Test
    fun `the walk should take whole rows and stop at the row cap`() {
        val (base, x) = chain()
        val idx = base.rowIndex()
        val capped = base.columnNeighborhood(intArrayOf(x[0]), maxRows = 1, rowIndex = idx)
        assertEquals(1, capped.model.m, "one row within the cap")
        assertEquals(2, capped.model.n, "the accepted row keeps its whole support")
        assertTrue(capped.colOf(x[0]) >= 0)
        assertTrue(capped.colOf(x[1]) >= 0)
        assertEquals(-1, capped.colOf(x[2]), "a column beyond the cap stays outside")
    }

    @Test
    fun `an uncapped walk should reach the whole connected component`() {
        val (base, x) = chain()
        val nb = base.columnNeighborhood(intArrayOf(x[0]), maxRows = 16, rowIndex = base.rowIndex())
        assertEquals(3, nb.model.m)
        assertEquals(3, nb.model.n)
    }

    @Test
    fun `the sub-model should carry bounds and slack relations per selected row`() {
        val b = LpBuilder()
        val x0 = b.addVar(2L, 9L)
        val y = b.addVar(0L, 3L)
        b.addRow(intArrayOf(x0, y), longArrayOf(1L, 1L), Relation.EQ, 5L)
        val base = b.build(Sense.MINIMIZE)
        val nb = base.columnNeighborhood(intArrayOf(x0), maxRows = 4, rowIndex = base.rowIndex())
        val c = nb.colOf(x0)
        assertEquals(base.upper[x0], nb.model.upper[c], "shifted column range carries over")
        assertEquals(base.loShift[x0], nb.model.loShift[c])
        assertTrue(nb.model.hasUpper[nb.model.slackCol(0)], "the equality row keeps its fixed slack")
    }

    @Test
    fun `a disconnected component should stay outside the neighborhood`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L)
        val z = b.addVar(0L, 10L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        b.addRow(intArrayOf(z), longArrayOf(1L), Relation.LE, 6L)
        val base = b.build(Sense.MINIMIZE)
        val nb = base.columnNeighborhood(intArrayOf(x), maxRows = 16, rowIndex = base.rowIndex())
        assertEquals(1, nb.model.m)
        assertEquals(-1, nb.colOf(z))
    }
}
