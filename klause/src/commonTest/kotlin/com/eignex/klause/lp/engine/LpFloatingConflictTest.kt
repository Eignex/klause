package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpFloatingConflictTest {
    @Test
    fun `either ray orientation should expose an inconsistent numerical projection`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)

        for (sign in listOf(-1.0, 1.0)) {
            val result = floatingInfeasibilityMargin(model, doubleArrayOf(sign))

            assertTrue(result.infeasible)
            assertEquals(1.0, result.margin)
            assertNull(result.stop)
            assertTrue(result.work > 0L)
        }
    }

    @Test
    fun `a feasible projection should provide no numerical refutation`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)

        val result = floatingInfeasibilityMargin(builder.build(Sense.MINIMIZE), doubleArrayOf(-1.0))

        assertFalse(result.infeasible)
        assertTrue(assertNotNull(result.margin) <= 0.0)
    }

    @Test
    fun `objective constants should not supply infeasibility authority`() {
        val model = LpBuilder().apply { addVar(2L, 3L, cost = 100L) }.build(Sense.MINIMIZE)

        val result = floatingInfeasibilityMargin(model, doubleArrayOf())

        assertFalse(result.infeasible)
        assertEquals(0.0, result.margin)
    }

    @Test
    fun `opposite ray attempts should share one work allowance`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)

        val result = floatingInfeasibilityMargin(builder.build(Sense.MINIMIZE), doubleArrayOf(1.0), workLimit = 2L)

        assertFalse(result.infeasible)
        assertNull(result.margin)
        assertEquals(2L, result.work)
        assertEquals(LpFloatingBoundStop.WORK, result.stop)
    }

    @Test
    fun `cancelled numerical conflict work should publish no refutation`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)

        val result = floatingInfeasibilityMargin(model, doubleArrayOf(), cancellation = Cancellation { true })

        assertFalse(result.infeasible)
        assertNull(result.margin)
        assertEquals(LpFloatingBoundStop.CANCELLED, result.stop)
    }

    @Test
    fun `a margin inside the declared tolerance should not refute the projection`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.00000001)

        val result = floatingInfeasibilityMargin(builder.build(Sense.MINIMIZE), doubleArrayOf(-1.0))

        assertFalse(result.infeasible)
        assertTrue(assertNotNull(result.margin) <= FLOAT_EVIDENCE_TOLERANCE)
    }
}
