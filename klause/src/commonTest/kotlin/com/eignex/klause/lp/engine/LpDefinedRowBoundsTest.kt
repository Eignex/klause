package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpDefinedRowBoundsTest {
    @Test
    fun `dense expansion width decline retains ordinary integer deductions`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 1L)
            val r = addRealVar(0.0, 0.25)
            val u = addRealVar(null, null)
            val v = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
            addRealRow(intArrayOf(y, u), doubleArrayOf(-1.0, 2.0.pow(-70)), Relation.EQ, 0.0)
            addRealRow(intArrayOf(v), doubleArrayOf(1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(u, v), doubleArrayOf(2.0.pow(70), 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.any { it.column == 0 && !it.upper && it.integerBound() == 1L })
    }

    @Test
    fun `dense rows project through shifted fractional real definitions`() {
        for (origin in listOf(0L, 3L)) for (sign in listOf(-1.0, 1.0)) for (selected in listOf(false, true)) {
            val source = LpBuilder().apply {
                val x = addVar(origin, origin + 1L)
                val r = addRealVar(null, null)
                val s = addRealVar(null, null)
                addRealRow(intArrayOf(x, r), doubleArrayOf(-sign, 0.5), Relation.EQ, 0.0)
                addRealRow(intArrayOf(s), doubleArrayOf(1.5), Relation.EQ, 0.0)
                addRealRow(intArrayOf(r, s), doubleArrayOf(0.5, 1.0), Relation.GE,
                    if (sign > 0.0) origin + 0.75 else -origin - 0.25)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val bounds = mixedRowBounds(model, affectedColumns = if (selected) intArrayOf(0) else null)

            assertTrue(bounds.any { it.column == 0 && it.upper == (sign < 0.0) &&
                it.integerBound() == origin + if (sign > 0.0) 1L else 0L })
        }
    }

    @Test
    fun `an undefined free real withholds a dense integer projection`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(null, null)
            val s = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(r, s), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.none { it.column == 0 })
    }

    @Test
    fun `cancellation withholds expanded row projections`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(null, null)
            val s = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(s), doubleArrayOf(1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(r, s), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model, Cancellation { true })

        assertTrue(bounds.isEmpty())
    }
}
