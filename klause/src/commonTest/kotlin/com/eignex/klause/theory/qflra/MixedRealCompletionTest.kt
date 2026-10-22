package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedRealCompletionTest {
    @Test
    fun `scaled parallel rows should fit the existing source dimension allowance`() {
        for (sign in listOf(-1L, 1L)) {
            val rows = (1L..130L).map { scale ->
                val coefficient = BigFraction.ofLong(sign * scale)
                ExactRationalInequality(intArrayOf(0, 1, 2, 3),
                    listOf(coefficient, BigFraction.ZERO, coefficient, coefficient), coefficient)
            }

            val point = assertNotNull(completeMixedRealComponents(4, rows, SourceLpBudget(), Cancellation.Never))

            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `parallel bounds should preserve strongest strict endpoints`() {
        for (strictFirst in listOf(false, true)) {
            val rows = List(127) {
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ofLong(2))
            } + listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE },
                    BigFraction.ONE, strictFirst),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ofLong(2) },
                    BigFraction.ofLong(2), !strictFirst),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ofLong(-2) },
                    BigFraction.MINUS_ONE),
            )

            val point = assertNotNull(completeMixedRealComponents(3, rows, SourceLpBudget(), Cancellation.Never))

            assertTrue(point.satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `opposite parallel bounds should remain contradictory`() {
        val rows = List(129) {
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO)
        } + ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE)

        val point = completeMixedRealComponents(3, rows, SourceLpBudget(), Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `independent real components should retain exact source row agreement`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
            ExactRationalInequality(
                intArrayOf(2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
        )

        val point = assertNotNull(completeMixedRealComponents(4, rows, SourceLpBudget(), Cancellation.Never))

        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `independent components should share one source operation allowance`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
            ExactRationalInequality(
                intArrayOf(2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
        )

        val point = completeMixedRealComponents(4, rows, SourceLpBudget(maxOperations = 1), Cancellation.Never)

        assertNull(point)
    }
}
