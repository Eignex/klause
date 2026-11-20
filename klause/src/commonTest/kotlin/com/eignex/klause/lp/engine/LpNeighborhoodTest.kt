package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.columnNeighborhood
import com.eignex.klause.lp.engine.rowIndex
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.withBoundedPolling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpNeighborhoodTest {

    @Test
    fun `short rebinds observe cancellation before returning an unchanged state`() {
        val (base, columns) = chain()
        val state = LpExactState(assertNotNull(base.authoritativeModel()))
        val working = assertNotNull(state.ownerWorkingModel())
        val neighborhood = working.columnNeighborhood(intArrayOf(columns[0]), 16, working.rowIndex())
        var observations = 0
        val stop = ExecutionControl(Cancellation { ++observations >= 3 }).withBoundedPolling()

        val rebound = neighborhood.rebindExact(state, stop)

        assertNull(rebound)
    }

    @Test
    fun `bound refreshes preserve suspended rows until their scope is popped`() {
        val (base, columns) = chain()
        val trail = LpBoundTrail(assertNotNull(base.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(2L)))
        val working = assertNotNull(trail.state.ownerWorkingModel())
        val neighborhood = working.columnNeighborhood(intArrayOf(columns[2]), 16, working.rowIndex())
        assertTrue(trail.assertBound(columns[0], false, ExactLpSide(ExactLpNumber.of(1L)), 0L))

        val refreshed = assertNotNull(neighborhood.rebindExact(trail.state, ExecutionControl.Never))
        assertTrue(trail.pop(0))
        val restored = assertNotNull(refreshed.rebindExact(trail.state, ExecutionControl.Never))

        val row = refreshed.rows.indexOf(2)
        val state = assertNotNull(refreshed.model.exactState)
        assertFalse(state.rows.row(row).active)
        assertNull(state.model.column(state.model.n + row).bounds.lower)
        assertEquals(ExactLpNumber.of(1L), state.model.column(refreshed.colOf(columns[0])).bounds.lower?.number)
        val next = assertNotNull(restored.model.exactState)
        assertTrue(next.rows.row(row).active)
        assertEquals(ExactLpNumber.of(0L), next.model.column(next.model.n + row).bounds.lower?.number)
        assertEquals(ExactLpNumber.of(0L), next.model.column(restored.colOf(columns[0])).bounds.lower?.number)
    }

    @Test
    fun `exact neighborhoods should retain full support when traversal reverses row order`() {
        val (base, x) = chain()
        val state = LpExactState(assertNotNull(base.authoritativeModel()))
        val working = assertNotNull(state.toWorkingModel())
        assertNotNull(working.doubleView).colVal.fill(0.0)

        val neighborhood = working.columnNeighborhood(intArrayOf(x[2]), 16, working.rowIndex())

        assertEquals(3, neighborhood.model.m)
        assertEquals(3, neighborhood.model.n)
        val restricted = assertNotNull(neighborhood.model.exactState).model
        for (column in neighborhood.cols.indices) {
            val original = neighborhood.cols[column]
            assertEquals(state.model.column(original), restricted.column(column))
            for (entry in restricted.entries(column)) {
                assertEquals(
                    state.model.entries(original).single { it.row == neighborhood.rows[entry.row] }.number,
                    entry.number,
                )
            }
        }
        assertTrue((0 until restricted.numVars).all { restricted.objective.cost(it).value.isZero })
    }

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
        assertEquals(1, capped.model.m)
        assertEquals(2, capped.model.n)
        assertTrue(capped.colOf(x[0]) >= 0)
        assertTrue(capped.colOf(x[1]) >= 0)
        assertEquals(-1, capped.colOf(x[2]))
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
        assertEquals(base.upper[x0], nb.model.upper[c])
        assertEquals(base.loShift[x0], nb.model.loShift[c])
        assertTrue(nb.model.hasUpper[nb.model.slackCol(0)])
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
