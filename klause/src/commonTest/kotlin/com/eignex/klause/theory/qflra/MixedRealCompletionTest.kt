package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedRealCompletionTest {
    @Test
    fun `parallel residual conflicts should avoid recovery LP operations`() {
        for (strict in listOf(false, true)) {
            for (reverse in listOf(false, true)) {
                val upper = ExactRationalInequality(intArrayOf(0, 1, 2),
                    List(3) { BigFraction.ofLong(2) }, if (strict) BigFraction.ofLong(2) else BigFraction.ZERO, strict)
                val lower = ExactRationalInequality(intArrayOf(0, 1, 2),
                    List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE)
                val rows = if (reverse) listOf(lower, upper) else listOf(upper, lower)
                val budget = SourceLpBudget(maxOperations = 0)
                var support: IntArray? = null

                val point = completeMixedRealComponents(3, rows, budget, Cancellation.Never,
                    onInfeasible = { support = it })

                assertNull(point)
                assertEquals(setOf(0, 1), assertNotNull(support).toSet())
                assertEquals(0, budget.operations)
            }
        }
    }

    @Test
    fun `certified residual conflicts should cite their original strongest rows`() {
        for (strict in listOf(false, true)) {
            val rows = List(127) {
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ofLong(2))
            } + listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ONE),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ofLong(2) },
                    if (strict) BigFraction.ofLong(2) else BigFraction.ZERO, strict),
                ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
                ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            )
            var support: IntArray? = null

            val point = completeMixedRealComponents(3, rows, SourceLpBudget(), Cancellation.Never,
                onInfeasible = { support = it })

            assertNull(point)
            assertEquals(setOf(128, 129, 130), assertNotNull(support).toSet())
        }
    }

    @Test
    fun `cancelled residual conflicts should withhold support`() {
        for (parallel in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
                ExactRationalInequality(if (parallel) intArrayOf(0, 1, 2) else intArrayOf(0, 1),
                    List(if (parallel) 3 else 2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
                ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            )
            var stopped = false
            var support: IntArray? = null
            val cancelledStage = if (parallel) "RECOVERY_REAL_COMPONENT_PARALLEL_CONFLICT"
                else "RECOVERY_REAL_COMPONENT_LP_INFEASIBLE"

            val point = completeMixedRealComponents(3, rows, SourceLpBudget(), Cancellation { stopped },
                onStage = { if (it == cancelledStage) stopped = true },
                onInfeasible = { support = it })

            assertTrue(stopped)
            assertNull(point)
            assertNull(support)
        }
    }

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
