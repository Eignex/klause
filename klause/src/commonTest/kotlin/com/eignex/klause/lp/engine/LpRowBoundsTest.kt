package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpRowBoundsTest {
    @Test
    fun `a real interval with no integer row activity should yield a conflict bound`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 1L)
            val r = addRealVar(0.25, 0.75)
            addRow(intArrayOf(x, y, r), longArrayOf(1L, 1L, 1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.any { it.column == 0 && it.upper && it.integerBound() == -1L })
    }

    @Test
    fun `integer row residues should exclude a binary endpoint left by interval projection`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 2L)
            val r = addRealVar(0.0, 1.0)
            addRow(intArrayOf(x, y, r), longArrayOf(3L, 4L, 1L), Relation.EQ, 5L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.any { it.column == 0 && it.upper && it.integerBound() == 0L })
    }

    @Test
    fun `integer row residues should retain source shifts and signed coefficients`() {
        for (sign in listOf(-1L, 1L)) {
            val source = LpBuilder().apply {
                val x = addVar(3L, 4L)
                val y = addVar(0L, 2L)
                val r = addRealVar(0.0, 1.0)
                addRow(
                    intArrayOf(x, y, r),
                    longArrayOf(3L * sign, 4L, 1L),
                    Relation.EQ,
                    if (sign > 0L) 14L else -7L,
                )
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val bounds = mixedRowBounds(model)

            assertTrue(
                bounds.any {
                    it.column == 0 && it.upper == (sign > 0L) &&
                        it.integerBound() == if (sign > 0L) 3L else 4L
                },
            )
        }
    }

    @Test
    fun `integer row residues should decline an absent real interval endpoint`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 2L)
            val r = addRealVar(0.0, null)
            addRow(intArrayOf(x, y, r), longArrayOf(3L, 4L, 1L), Relation.EQ, 5L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.none { it.column == 0 && it.integerBound() == 0L })
    }

    @Test
    fun `strict real endpoints should exclude an equal integer residue`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 2L)
            val r = addRealVar(0.0, 1.0)
            addRow(intArrayOf(x, y, r), longArrayOf(3L, 4L, 1L), Relation.EQ, 5L)
        }.build(Sense.MINIMIZE)
        val exact = assertNotNull(source.authoritativeModel())
        val real = exact.column(2)
        val changed = exact.withBoundColumns(
            List(exact.numVars) {
                if (it == 2) {
                    real.copy(bounds = real.bounds.copy(upper = real.bounds.upper?.copy(strict = true)))
                } else {
                    exact.column(it)
                }
            },
        )
        val model = assertNotNull(LpExactState(changed).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.any { it.column == 0 && it.upper && it.integerBound() == -1L })
    }

    @Test
    fun `row projections should retain independent bounds across integral coordinates`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 5L)
            val y = addVar(0L, 4L)
            val r = addRealVar(0.0, 1.0)
            addRow(intArrayOf(x, y, r), longArrayOf(1L, 2L, 1L), Relation.LE, 4L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertEquals(listOf(0, 1), bounds.map { it.column })
        assertEquals(listOf(4L, 2L), bounds.map { it.integerBound() })
    }

    @Test
    fun `integral valued real endpoints should preserve strict source coordinate projection`() {
        for (origin in listOf(0L, 3L)) {
            for (sign in listOf(-1.0, 1.0)) {
                for (strict in listOf(false, true)) {
                    val source = LpBuilder().apply {
                        val x = addVar(origin, origin + 2L)
                        val r = addRealVar(0.0, 1.0)
                        addRealRow(
                            intArrayOf(x, r),
                            doubleArrayOf(sign, 1.0),
                            Relation.GE,
                            if (sign > 0.0) origin.toDouble() + 2.0 else -origin.toDouble(),
                            strict = strict,
                        )
                    }.build(Sense.MINIMIZE)
                    val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

                    val bound = mixedRowBounds(model).single()

                    assertEquals(sign < 0.0, bound.upper)
                    assertEquals(strict, bound.strict)
                    assertEquals(
                        origin + if (!strict) {
                            1L
                        } else if (sign > 0.0) {
                            2L
                        } else {
                            0L
                        },
                        bound.integerBound(),
                    )
                }
            }
        }
    }

    @Test
    fun `integral row products beyond Long should retain exact coordinate bounds`() {
        val zero = ExactLpNumber.of(0L)
        val wide = ExactLpNumber.of(Long.MAX_VALUE)
        val source = ExactLpModel(
            List(2) { listOf(ExactLpEntry(0, wide)) },
            listOf(wide),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertTrue(bound.upper)
        assertEquals(1L, bound.integerBound())
    }

    @Test
    fun `signed row accumulator overflow should retain exact rational projection`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val wide = ExactLpNumber.of(Long.MAX_VALUE)
        val source = ExactLpModel(
            List(4) { column -> listOf(ExactLpEntry(0, if (column == 0) one else wide)) },
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L))))) +
                List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(wide)), integral = false) } +
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            listOf(ExactLpRow()),
            ExactLpObjective(List(5) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val bound = mixedRowBounds(model).single()

        assertTrue(bound.upper)
        assertEquals(1L, bound.integerBound())
    }

    @Test
    fun `mixed rows should project fractional real endpoints onto both integer bound directions`() {
        for (sign in listOf(-1.0, 1.0)) {
            val source = LpBuilder().apply {
                val x = addVar(0L, 1L, cost = 2L)
                val r = addRealVar(0.0, 0.25, cost = 0.5)
                addRealRow(
                    intArrayOf(x, r),
                    doubleArrayOf(sign, 1.0),
                    Relation.GE,
                    if (sign > 0.0) 0.75 else -0.25,
                )
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
    fun `strict row endpoints should exclude an equal integer coordinate`() {
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
    fun `an absent supporting real endpoint should not use the working probe bound`() {
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
    fun `row certificates should not cross supporting bound rollback`() {
        val source = assertNotNull(
            LpBuilder().apply {
                val x = addVar(0L, 1L)
                val r = addRealVar(0.0, 1.0)
                addRealRow(intArrayOf(x, r), doubleArrayOf(1.0, 1.0), Relation.GE, 0.75)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(
            trail.assertBound(
                1,
                true,
                ExactLpSide(ExactLpNumber.of(assertNotNull(BigFraction.ofDouble(0.25)))),
                7L,
            ),
        )
        val child = assertNotNull(trail.state.toWorkingModel())
        val bound = mixedRowBounds(child).single()
        assertTrue(trail.pop(0))

        assertFalse(bound.belongsTo(assertNotNull(trail.state.toWorkingModel())))
    }

    @Test
    fun `cancelled row certification should discard its deductions`() {
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
    fun `oversized row arithmetic should withhold coordinate deductions`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 0.25)
            addRealRow(intArrayOf(x, r), doubleArrayOf(1.0e40, 1.0), Relation.GE, 0.75e40)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val bounds = mixedRowBounds(model)

        assertTrue(bounds.isEmpty())
    }

    @Test
    fun `paired rows should fix shifted integers without a continuous upper endpoint`() {
        for (origin in listOf(0L, 3L)) {
            for (sign in listOf(-1.0, 1.0)) {
                for (selected in listOf(false, true)) {
                    val source = LpBuilder().apply {
                        val x = addVar(origin, origin + 1L)
                        val r = addRealVar(0.0, null)
                        addRealRow(
                            intArrayOf(x, r),
                            doubleArrayOf(sign, 0.5),
                            Relation.GE,
                            if (sign > 0.0) origin + 1.25 else -origin - 0.25,
                        )
                        addRealRow(intArrayOf(r), doubleArrayOf(1.5), Relation.LE, 0.75)
                    }.build(Sense.MINIMIZE)
                    val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

                    val bounds = mixedRowBounds(model, affectedColumns = if (selected) intArrayOf(0) else null)

                    assertTrue(
                        bounds.any {
                            it.column == 0 && it.upper == (sign < 0.0) &&
                                it.integerBound() == origin + if (sign > 0.0) 1L else 0L
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `another continuous term should prevent singleton pair projection`() {
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
    fun `same direction continuous inequalities should not force an integer endpoint`() {
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

    @Test
    fun `a dense expansion width decline should retain ordinary integer deductions`() {
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
    fun `dense rows should project through shifted fractional real definitions`() {
        for (origin in listOf(0L, 3L)) {
            for (sign in listOf(-1.0, 1.0)) {
                for (selected in listOf(false, true)) {
                    val source = LpBuilder().apply {
                        val x = addVar(origin, origin + 1L)
                        val r = addRealVar(null, null)
                        val s = addRealVar(null, null)
                        addRealRow(intArrayOf(x, r), doubleArrayOf(-sign, 0.5), Relation.EQ, 0.0)
                        addRealRow(intArrayOf(s), doubleArrayOf(1.5), Relation.EQ, 0.0)
                        addRealRow(
                            intArrayOf(r, s),
                            doubleArrayOf(0.5, 1.0),
                            Relation.GE,
                            if (sign > 0.0) origin + 0.75 else -origin - 0.25,
                        )
                    }.build(Sense.MINIMIZE)
                    val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

                    val bounds = mixedRowBounds(model, affectedColumns = if (selected) intArrayOf(0) else null)

                    assertTrue(
                        bounds.any {
                            it.column == 0 && it.upper == (sign < 0.0) &&
                                it.integerBound() == origin + if (sign > 0.0) 1L else 0L
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `an undefined free real should withhold a dense integer projection`() {
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
}
