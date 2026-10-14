package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpRowBoundsTest {
    @Test
    fun `integral valued real endpoints preserve strict source coordinate projection`() {
        for (origin in listOf(0L, 3L)) for (sign in listOf(-1.0, 1.0)) for (strict in listOf(false, true)) {
            val source = LpBuilder().apply {
                val x = addVar(origin, origin + 2L)
                val r = addRealVar(0.0, 1.0)
                addRealRow(intArrayOf(x, r), doubleArrayOf(sign, 1.0), Relation.GE,
                    if (sign > 0.0) origin.toDouble() + 2.0 else -origin.toDouble(), strict = strict)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val bound = mixedRowBounds(model).single()

            assertEquals(sign < 0.0, bound.upper)
            assertEquals(strict, bound.strict)
            assertEquals(origin + if (!strict) 1L else if (sign > 0.0) 2L else 0L, bound.integerBound())
        }
    }

    @Test
    fun `integral row products beyond Long retain exact coordinate bounds`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L)
            val r = addRealVar(0.0, 1.0)
            addRow(intArrayOf(x, r), longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), Relation.LE, Long.MAX_VALUE)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertTrue(bound.upper)
        assertEquals(1L, bound.integerBound())
    }

    @Test
    fun `signed row accumulator overflow retains exact rational projection`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 2L)
            val r = addRealVar(0.0, 1.0)
            val s = addRealVar(0.0, 1.0)
            val t = addRealVar(0.0, 1.0)
            addRow(intArrayOf(x, r, s, t), longArrayOf(1L, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                Relation.LE, 1L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val columns = List(source.numVars) { column ->
            val original = source.column(column)
            if (column in 1..3) original.copy(bounds = original.bounds.copy(
                upper = ExactLpSide(ExactLpNumber.of(Long.MAX_VALUE)),
            )) else original
        }
        val model = assertNotNull(LpExactState(source.withBoundColumns(columns)).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertTrue(bound.upper)
        assertEquals(1L, bound.integerBound())
    }

    @Test
    fun `mixed rows project fractional real endpoints onto both integer bound directions`() {
        for (sign in listOf(-1.0, 1.0)) {
            val source = LpBuilder().apply {
                val x = addVar(0L, 1L, cost = 2L)
                val r = addRealVar(0.0, 0.25, cost = 0.5)
                addRealRow(intArrayOf(x, r), doubleArrayOf(sign, 1.0), Relation.GE,
                    if (sign > 0.0) 0.75 else -0.25)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val bounds = mixedRowBounds(model)

            val bound = bounds.single()
            assertEquals(0, bound.column)
            assertEquals(sign < 0.0, bound.upper)
            assertEquals(if (sign > 0.0) 1L else 0L, bound.integerBound())
            assertTrue(bound.belongsTo(model))
        }
    }

    @Test
    fun `strict row endpoints exclude an equal integer coordinate`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 2L)
            val r = addRealVar(0.0, 0.25)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 1.25, strict = true)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertTrue(bound.strict)
        assertEquals(BigFraction.ONE, bound.value)
        assertEquals(2L, bound.integerBound())
    }

    @Test
    fun `an absent supporting real endpoint cannot use the working probe bound`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(null, null)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `row bounds reconstruct shifted integer coordinates`() {
        val source = LpBuilder().apply {
            val x = addVar(3L, 4L)
            val r = addRealVar(0.0, 0.25)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 3.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertEquals(4L, bound.integerBound())
    }

    @Test
    fun `row certificates cannot cross supporting bound rollback`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(1, true,
            ExactLpSide(ExactLpNumber.of(assertNotNull(BigFraction.ofDouble(0.25)))), 7L))
        val child = assertNotNull(trail.state.toWorkingModel())
        val bound = mixedRowBounds(child).single()
        assertTrue(trail.pop(0))

        assertFalse(bound.belongsTo(assertNotNull(trail.state.toWorkingModel())))
    }

    @Test
    fun `cancelled row certification discards its deductions`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 0.25)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model, Cancellation { true })

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `oversized row arithmetic withholds coordinate deductions`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 0.25)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0e40, 1.0), Relation.GE, 0.75e40)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.isEmpty())
    }
}
