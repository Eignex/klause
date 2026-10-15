package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpRowPairBoundsTest {
    @Test
    fun `paired rows fix shifted integers without a continuous upper endpoint`() {
        for (origin in listOf(0L, 3L)) for (sign in listOf(-1.0, 1.0)) for (selected in listOf(false, true)) {
            val source = LpBuilder().apply {
                val x = addVar(origin, origin + 1L)
                val r = addRealVar(0.0, null)
                addRealRow(intArrayOf(x, r), doubleArrayOf(sign, 0.5), Relation.GE,
                    if (sign > 0.0) origin + 1.25 else -origin - 0.25)
                addRealRow(intArrayOf(r), doubleArrayOf(1.5), Relation.LE, 0.75)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val bounds = mixedRowBounds(model, affectedColumns = if (selected) intArrayOf(0) else null)

            assertTrue(bounds.any { it.column == 0 && it.upper == (sign < 0.0) &&
                it.integerBound() == origin + if (sign > 0.0) 1L else 0L })
        }
    }

    @Test
    fun `another continuous term prevents singleton pair projection`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, null)
            val s = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x, r, s), doubleArrayOf(1.0, 1.0, 1.0), Relation.GE, 1.25)
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.LE, 0.25)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.none { it.column == 0 && !it.upper && it.integerBound() == 1L })
    }

    @Test
    fun `same direction continuous inequalities do not force an integer endpoint`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 1.25)
            addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.GE, 0.25)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.none { it.column == 0 })
    }
}
