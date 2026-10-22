package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedPointCompletionTest {
    @Test
    fun `independent source conflicts should retain their original row support`() {
        val rows = listOf(
            exactColumnUpper(424, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            exactColumnLower(2, BigFraction.ZERO), exactColumnUpper(2, BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ONE),
        )
        var support: IntArray? = null

        val point = completeMixedPoint(425, 0, DoubleArray(425), rows, Cancellation.Never,
            onSourceInfeasible = { support = it })

        assertNull(point)
        assertEquals((1..7).toSet(), assertNotNull(support).toSet())
    }

    @Test
    fun `cancelled independent source conflicts should withhold support`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            exactColumnLower(2, BigFraction.ZERO), exactColumnUpper(2, BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ONE),
        )
        var stopped = false
        var sourceInfeasible = false

        val point = completeMixedPoint(425, 0, DoubleArray(425), rows, Cancellation { stopped },
            onStage = { if (it == "RECOVERY_SOURCE_COMPONENT_CONFLICT") stopped = true },
            onSourceInfeasible = { sourceInfeasible = true })

        assertTrue(stopped)
        assertNull(point)
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `independent real blocks should share the equality repair allowance`() {
        for (size in listOf(3, 140)) {
            val columns = 3 * size + 5
            val rows = buildList {
                for (start in 0 until 3 * size step size) {
                    val equation = ExactRationalInequality(intArrayOf(start, start + 1, start + 2),
                        listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO)
                    add(equation)
                    add(ExactRationalInequality(equation.columns, equation.coefficients.map { it.negated() },
                        BigFraction.ZERO))
                    for (column in start + 2 until start + size - 1) {
                        add(ExactRationalInequality(intArrayOf(column, column + 1),
                            listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO))
                        add(ExactRationalInequality(intArrayOf(column, column + 1),
                            listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO))
                    }
                    add(ExactRationalInequality(intArrayOf(start), listOf(BigFraction.MINUS_ONE),
                        BigFraction.ZERO, strict = true))
                }
            }
            val primal = DoubleArray(columns).also {
                for (start in 0 until 3 * size step size) it[start + size - 1] = 0.5
                it[columns - 1] = 0.75
            }

            val point = assertNotNull(completeMixedPoint(columns, 0, primal, rows, Cancellation.Never))

            assertTrue(point.satisfiesSourceRows(rows))
            assertEquals(BigFraction.ofLong(4).reciprocal() * BigFraction.ofLong(3), point.last())
        }
    }

    @Test
    fun `independent real blocks should retain the combined repair limit`() {
        val rows = buildList {
            for (start in 0 until 450 step 150) {
                for (column in start until start + 148) {
                    add(ExactRationalInequality(intArrayOf(column, column + 1, column + 2),
                        listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO))
                    add(ExactRationalInequality(intArrayOf(column, column + 1, column + 2),
                        listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO))
                }
                add(exactColumnLower(start + 149, BigFraction.ONE))
            }
        }
        var sourceInfeasible = false

        val point = completeMixedPoint(450, 0, DoubleArray(450), rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true })

        assertNull(point)
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `cancelled independent real repairs should withhold their point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 211, 424),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 211, 424),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(424, BigFraction.ofLong(2).reciprocal()),
        )
        val primal = DoubleArray(425).also { it[424] = 0.5 }
        var stopped = false
        var sourceInfeasible = false

        val point = completeMixedPoint(425, 0, primal, rows, Cancellation { stopped },
            onStage = { if (it == "RECOVERY_EQUALITY_RECONSTRUCTED") stopped = true },
            onSourceInfeasible = { sourceInfeasible = true })

        assertTrue(stopped)
        assertNull(point)
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `large real prefixes should retain checked point candidates`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 424), List(2) { BigFraction.ONE }, BigFraction.ONE, strict = true),
            exactColumnLower(424, BigFraction.ofLong(4).reciprocal()),
        )
        val primal = DoubleArray(425).also {
            it[0] = 0.25
            it[424] = 0.5
        }
        var sourceInfeasible = false

        val point = assertNotNull(completeMixedPoint(425, 0, primal, rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true }))

        assertEquals(425, point.size)
        assertTrue(point.satisfiesSourceRows(rows))
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `large real contradictions should withhold equality repair refutations`() {
        val rows = buildList {
            addAll(listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
                exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
                exactColumnLower(2, BigFraction.ZERO), exactColumnUpper(2, BigFraction.ZERO),
                exactColumnLower(0, BigFraction.ONE),
            ))
            for (column in 2 until 424) {
                add(ExactRationalInequality(intArrayOf(column, column + 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO))
                add(ExactRationalInequality(intArrayOf(column, column + 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO))
            }
        }
        var sourceInfeasible = false

        val point = completeMixedPoint(425, 0, DoubleArray(425), rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true })

        assertNull(point)
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `residual LP conflicts should retain equality derivation premises`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                exactColumnUpper(4, BigFraction.ONE),
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 3), List(2) { BigFraction.ONE },
                    if (strict) BigFraction.ONE else BigFraction.ZERO, strict),
                ExactRationalInequality(intArrayOf(1, 2, 3), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            )
            val budget = SourceLpBudget()
            var support: IntArray? = null

            val point = completeMixedPoint(5, 0, DoubleArray(5), rows, Cancellation.Never,
                onSourceInfeasible = { support = it },
                realCompletion = { columns, residual, stop, onInfeasible ->
                    completeMixedRealComponents(columns, residual, budget, stop, onInfeasible = onInfeasible)
                })

            assertNull(point)
            assertEquals(setOf(1, 2, 3, 4), assertNotNull(support).toSet())
        }
    }

    @Test
    fun `residual LP conflicts after integer guesses should withhold source refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 3), List(2) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2, 3, 4), List(4) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val budget = SourceLpBudget()
        var sourceInfeasible = false
        var residualInfeasible = false

        val point = completeMixedPoint(4, 1, DoubleArray(5), rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true },
            realCompletion = { columns, residual, stop, onInfeasible ->
                completeMixedRealComponents(columns, residual, budget, stop,
                    onStage = { if (it == "RECOVERY_REAL_COMPONENT_LP_INFEASIBLE") residualInfeasible = true },
                    onInfeasible = onInfeasible)
            })

        assertTrue(residualInfeasible)
        assertNull(point)
        assertFalse(sourceInfeasible)
        assertTrue(listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
            .satisfiesSourceRows(rows))
    }

    @Test
    fun `source contradictions should exclude unused original rows from their support`() {
        val rows = listOf(
            exactColumnUpper(3, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ZERO),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
        )
        var support: IntArray? = null

        val point = completeMixedPoint(4, 0, DoubleArray(4), rows, Cancellation.Never,
            onSourceInfeasible = { support = it })

        assertNull(point)
        assertEquals((2..8).toSet(), assertNotNull(support).toSet())
    }

    @Test
    fun `pure source equality contradictions should report infeasibility`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
                exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ZERO),
                exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict),
            )
            var sourceInfeasible = false

            val point = completeMixedPoint(3, 0, DoubleArray(3), rows, Cancellation.Never,
                onSourceInfeasible = { sourceInfeasible = true })

            assertNull(point)
            assertTrue(sourceInfeasible)
        }
    }

    @Test
    fun `a failed integer guess should not refute the source model`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ZERO),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2, 3), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        var sourceInfeasible = false

        val point = completeMixedPoint(3, 1, DoubleArray(4), rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true })

        assertNull(point)
        assertFalse(sourceInfeasible)
        assertTrue(listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
            .satisfiesSourceRows(rows))
    }

    @Test
    fun `cancelled equality contradictions should withhold source infeasibility`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ZERO),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
        )
        var stopped = false
        var sourceInfeasible = false

        val point = completeMixedPoint(3, 0, DoubleArray(3), rows, Cancellation { stopped },
            onStage = { if (it == "RECOVERY_EQUALITY_RESIDUAL_FALSE") stopped = true },
            onSourceInfeasible = { sourceInfeasible = true })

        assertTrue(stopped)
        assertNull(point)
        assertFalse(sourceInfeasible)
    }

    @Test
    fun `pure real strict residual completion should replace a boundary guess`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )

        var sourceInfeasible = false

        val point = assertNotNull(completeMixedPoint(1, 0, doubleArrayOf(0.0), rows, Cancellation.Never,
            onSourceInfeasible = { sourceInfeasible = true }))

        assertFalse(sourceInfeasible)
        assertTrue(point[0] > BigFraction.ZERO && point[0] < BigFraction.ONE)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `source prefixes should satisfy rows outside the difference fragment`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ONE.negated()),
                BigFraction.ZERO,
            ),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE.negated(), BigFraction.ONE.negated(), BigFraction.ONE),
                BigFraction.ZERO,
            ),
        )

        for (integerColumns in listOf(0, 1)) {
            val point = assertNotNull(
                completeMixedPoint(
                    3,
                    integerColumns,
                    doubleArrayOf(0.5, 0.5, 1.0, 1.0).copyOf(3 + integerColumns),
                    rows,
                    Cancellation.Never,
                ),
            )

            assertEquals(3 + integerColumns, point.size)
            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `rational reconstruction of source reals should require exact full row agreement`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                List(3) { BigFraction.ONE.negated() },
                BigFraction.ONE.negated(),
            ),
        )

        for (integerColumns in listOf(0, 1)) {
            val point = assertNotNull(
                completeMixedPoint(
                    3,
                    integerColumns,
                    doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3, 1.0).copyOf(3 + integerColumns),
                    rows,
                    Cancellation.Never,
                ),
            )

            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `opposite source rows should repair coupled real equalities before full validation`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO,
            ),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO,
            ),
            exactColumnLower(0, third),
            exactColumnUpper(0, third),
            exactColumnLower(1, third),
            exactColumnUpper(1, third),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )

        for (integerColumns in listOf(0, 1)) {
            val point = assertNotNull(
                completeMixedPoint(
                    3,
                    integerColumns,
                    doubleArrayOf(1.0 / 3, 1.0 / 3, 0.7, 1.0).copyOf(3 + integerColumns),
                    rows,
                    Cancellation.Never,
                ),
            )

            assertEquals(third + third, point[2])
            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `eliminated source equations should permit strict completion of free real coordinates`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO,
            ),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO,
            ),
            exactColumnLower(1, BigFraction.ZERO),
            exactColumnUpper(1, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )

        val point = assertNotNull(
            completeMixedPoint(
                3,
                1,
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),
                rows,
                Cancellation.Never,
            ),
        )

        assertTrue(point[0] > BigFraction.ZERO && point[0] < BigFraction.ONE)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `small general residuals should complete under the retained source allowance`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO,
            ),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO,
            ),
            exactColumnLower(1, BigFraction.ZERO), exactColumnUpper(1, BigFraction.ZERO),
            exactColumnLower(2, BigFraction.ZERO), exactColumnUpper(2, BigFraction.ONE),
            exactColumnLower(3, BigFraction.ZERO), exactColumnUpper(3, BigFraction.ONE),
            ExactRationalInequality(
                intArrayOf(2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
                strict = true,
            ),
        )
        val budget = SourceLpBudget()

        val point = assertNotNull(
            completeMixedPoint(
                4,
                1,
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
                rows,
                Cancellation.Never,
                realCompletion = { columns, residual, stop, onInfeasible ->
                    completeMixedRealComponents(columns, residual, budget, stop, onInfeasible = onInfeasible)
                },
            ),
        )

        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `repaired equalities should not bypass real inequalities`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE),
                BigFraction.ZERO,
            ),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO,
            ),
            exactColumnLower(0, BigFraction.ONE),
            exactColumnUpper(0, BigFraction.ONE),
            exactColumnLower(1, BigFraction.ONE),
            exactColumnUpper(1, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )

        val point = completeMixedPoint(3, 1, doubleArrayOf(1.0, 1.0, 0.7, 1.0), rows, Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `integer guesses should admit exact strict real completion only after full row checking`() {
        val rows = listOf(
            exactColumnLower(0, BigFraction.ZERO),
            exactColumnUpper(1, BigFraction.ofLong(3)),
            exactColumnLower(2, BigFraction.ONE),
            exactColumnUpper(2, BigFraction.ONE),
            ExactRationalInequality(
                intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE.negated(), BigFraction.ONE),
                BigFraction.ZERO,
                strict = true,
            ),
        )

        val point = assertNotNull(
            completeMixedPoint(
                2,
                1,
                doubleArrayOf(0.0, 0.0, 1.0),
                rows,
                Cancellation.Never,
            ),
        )

        assertEquals(BigFraction.ONE, point[2])
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `oversized intermediate products should withhold an otherwise cancelling guess`() {
        val coefficient = BigFraction.of(BIG_ONE shl 4095, BIG_ONE)
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(1, 2),
                listOf(coefficient, coefficient.negated()),
                BigFraction.ZERO,
            ),
        )

        val point = completeMixedPoint(
            1,
            2,
            doubleArrayOf(0.0, Double.MAX_VALUE, Double.MAX_VALUE),
            rows,
            Cancellation.Never,
        )

        assertNull(point)
    }

    @Test
    fun `rounded integers should not escape current branch bounds`() {
        val rows = listOf(exactColumnLower(1, BigFraction.ONE), exactColumnUpper(1, BigFraction.ONE))
        for (value in listOf(1.6, -0.6)) {
            val point = completeMixedPoint(1, 1, doubleArrayOf(0.0, value), rows, Cancellation.Never)

            assertNull(point)
        }
    }

    @Test
    fun `invalid non difference residuals should decline the integer guess`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE),
                BigFraction.ONE,
            ),
        )

        val point = completeMixedPoint(2, 1, doubleArrayOf(2.0, 2.0, 1.0), rows, Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `inconsistent real residuals should not produce a witness`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ONE), exactColumnUpper(0, BigFraction.ZERO))

        for (integerColumns in listOf(0, 1)) {
            val point = completeMixedPoint(1, integerColumns, doubleArrayOf(0.0, 1.0).copyOf(1 + integerColumns),
                rows, Cancellation.Never)

            assertNull(point)
        }
    }

    @Test
    fun `stopped completion should retain caller work without publishing a point`() {
        for (integerColumns in listOf(0, 1)) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = charged > 0L
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }

            val point = completeMixedPoint(1, integerColumns, DoubleArray(1 + integerColumns), emptyList(), stop)

            assertNull(point)
            assertTrue(charged > 0L)
        }
    }
}
