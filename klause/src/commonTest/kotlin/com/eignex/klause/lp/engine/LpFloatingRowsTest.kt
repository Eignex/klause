package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpFloatingRowsTest {
    @Test
    fun `a mixed row should project finite real endpoints onto an integer column`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 2L)
        val r = builder.addRealVar(0.0, 0.25)
        builder.addRow(intArrayOf(x, r), longArrayOf(1L, 1L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)

        val result = floatingRowBounds(model, 10000L, 1024L * 1024L)

        assertTrue(result.bounds.any {
            it.column == x && !it.upper && it.shiftedValue > 0.5 && it.shiftedValue <= 0.75
        })
        assertTrue(result.bounds.all { it.belongsTo(model) })
        assertFalse(result.bounds.first().belongsTo(builder.build(Sense.MINIMIZE)))
        assertTrue(result.work > 0L)
    }

    @Test
    fun `paired rows should eliminate a shared continuous column without an endpoint`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val pin = builder.addVar(0L, 0L)
        val r = builder.addRealVar(0.0, null)
        builder.addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 1.25)
        builder.addRealRow(intArrayOf(r, pin), doubleArrayOf(1.0, -1.0), Relation.LE, 0.25)

        val result = floatingRowBounds(builder.build(Sense.MINIMIZE), 10000L, 1024L * 1024L)

        assertTrue(result.bounds.any {
            it.column == x && !it.upper && it.shiftedValue > 0.5 && it.shiftedValue <= 1.0
        })
    }

    @Test
    fun `singleton definitions should project a dense real row onto its integer sources`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val pin = builder.addVar(0L, 0L)
        val r = builder.addRealVar(0.0, null)
        val s = builder.addRealVar(0.0, null)
        builder.addRow(intArrayOf(r, x), longArrayOf(1L, -1L), Relation.EQ, 0L)
        builder.addRow(intArrayOf(s, pin), longArrayOf(1L, -1L), Relation.EQ, 0L)
        builder.addRealRow(intArrayOf(r, s), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)

        val result = floatingRowBounds(builder.build(Sense.MINIMIZE), 20000L, 1024L * 1024L)

        assertTrue(result.bounds.any {
            it.column == x && !it.upper && it.shiftedValue > 0.5 && it.shiftedValue <= 0.75
        })
    }

    @Test
    fun `rounding residuals should preserve a feasible wide source point`() {
        val rhs = 24_019_198_012_642_648L
        val cap = 72_057_594_037_927_936L
        val real = rhs - 3L
        val builder = LpBuilder()
        val x = builder.addVar(0L, 6L)
        val r = builder.addRealVar(0.0, null)
        builder.addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, rhs.toDouble())
        builder.addRealRow(intArrayOf(r), doubleArrayOf(3.0), Relation.LE, cap.toDouble())

        val result = floatingRowBounds(builder.build(Sense.MINIMIZE), 10000L, 1024L * 1024L)

        assertEquals(rhs, 3L + real)
        assertTrue(3L * real <= cap)
        assertTrue(result.bounds.filter { it.column == x && !it.upper }.all { it.shiftedValue <= 3.0 })
    }

    @Test
    fun `a spent row allowance should publish no projection`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 2L)
        val r = builder.addRealVar(0.0, 0.25)
        builder.addRow(intArrayOf(x, r), longArrayOf(1L, 1L), Relation.GE, 1L)

        val result = floatingRowBounds(builder.build(Sense.MINIMIZE), 0L, 1024L * 1024L)

        assertEquals(0L, result.work)
        assertEquals(LpFloatingBoundStop.WORK, result.stop)
        assertTrue(result.bounds.isEmpty())
    }

    @Test
    fun `cancellation should withhold partially inspected row bounds`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 2L)
        val r = builder.addRealVar(0.0, 0.25)
        builder.addRow(intArrayOf(x, r), longArrayOf(1L, 1L), Relation.GE, 1L)
        var polls = 0

        val result = floatingRowBounds(builder.build(Sense.MINIMIZE), 10000L, 1024L * 1024L,
            Cancellation { ++polls >= 3 })

        assertEquals(LpFloatingBoundStop.CANCELLED, result.stop)
        assertTrue(result.work > 0L)
        assertTrue(result.bounds.isEmpty())
    }
}
