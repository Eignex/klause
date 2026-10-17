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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedPointCompletionTest {
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

        val point = assertNotNull(
            completeMixedPoint(
                3,
                1,
                doubleArrayOf(0.5, 0.5, 1.0, 1.0),
                rows,
                Cancellation.Never,
            ),
        )

        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `rational reconstruction of source reals should require exact full row agreement`() {
        for (integerColumns in listOf(0, 1)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ONE),
                ExactRationalInequality(
                    intArrayOf(0, 1, 2),
                    List(3) { BigFraction.ONE.negated() },
                    BigFraction.ONE.negated(),
                ),
            )

            val point = assertNotNull(
                completeMixedPoint(
                    3,
                    integerColumns,
                    doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3, 1.0).copyOf(3 + integerColumns),
                    rows,
                    Cancellation.Never,
                ),
            )

            assertEquals(3 + integerColumns, point.size)
            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `opposite source rows should repair coupled real equalities before full validation`() {
        for (integerColumns in listOf(0, 1)) {
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
            assertEquals(3 + integerColumns, point.size)
            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `eliminated source equations should permit strict completion of free real coordinates`() {
        for (integerColumns in listOf(0, 1)) {
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
                    integerColumns,
                    doubleArrayOf(0.0, 0.0, 0.0, 1.0).copyOf(3 + integerColumns),
                    rows,
                    Cancellation.Never,
                ),
            )

            assertTrue(point[0] > BigFraction.ZERO && point[0] < BigFraction.ONE)
            assertEquals(3 + integerColumns, point.size)
            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `small general residuals should complete under the retained source allowance`() {
        for (integerColumns in listOf(0, 1)) {
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
                    integerColumns,
                    doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0).copyOf(4 + integerColumns),
                    rows,
                    Cancellation.Never,
                    realCompletion = { columns, residual, stop ->
                        completeMixedRealComponents(columns, residual, budget, stop)
                    },
                ),
            )

            assertEquals(4 + integerColumns, point.size)
            assertTrue(point.satisfiesSourceRows(rows))
        }
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

        val point = completeMixedPoint(1, 1, doubleArrayOf(0.0, 1.0), rows, Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `stopped completion should retain caller work without publishing a point`() {
        for (integerColumns in listOf(0, 1)) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = charged > 0L
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }

            val point = completeMixedPoint(1, integerColumns, doubleArrayOf(0.0, 1.0).copyOf(1 + integerColumns), emptyList(), stop)

            assertNull(point)
            assertTrue(charged > 0L)
        }
    }
}
