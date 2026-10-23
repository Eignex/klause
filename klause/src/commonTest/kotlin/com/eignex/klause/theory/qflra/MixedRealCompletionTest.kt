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
    fun `partial ceilings should preserve source operations for completion`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val budget = SourceLpBudget(maxOperations = 2)
        val stages = ArrayList<String>()
        assertNotNull(completeMixedRealComponents(3, rows, budget, Cancellation.Never, operationCeiling = 1))

        val partial = completeMixedRealComponents(3, rows, budget, Cancellation.Never,
            onStage = { stages += it }, operationCeiling = 1)
        val operationsAfterPartial = budget.operations
        val complete = completeMixedRealComponents(3, rows, budget, Cancellation.Never)

        assertNull(partial)
        assertEquals(1, operationsAfterPartial)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_RESERVED" in stages)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_REQUEST" !in stages)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_null" !in stages)
        assertTrue(assertNotNull(complete).satisfiesSourceRows(rows))
        assertEquals(2, budget.operations)
    }

    @Test
    fun `completion should retain the shared source operation cap`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val budget = SourceLpBudget(maxOperations = 1)
        val stages = ArrayList<String>()
        assertNotNull(completeMixedRealComponents(3, rows, budget, Cancellation.Never))

        val point = completeMixedRealComponents(3, rows, budget, Cancellation.Never, onStage = { stages += it })

        assertNull(point)
        assertEquals(1, budget.operations)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_null" in stages)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_RESERVED" !in stages)
    }

    @Test
    fun `cheap conflicts should remain available at the partial ceiling`() {
        for (kind in listOf("graph", "parallel")) {
            for (strict in listOf(false, true)) {
                val columns = if (kind == "parallel") intArrayOf(0, 1, 2) else intArrayOf(0)
                val rows = listOf(
                    ExactRationalInequality(columns, List(columns.size) { BigFraction.ONE },
                        if (strict) BigFraction.ONE else BigFraction.ZERO, strict),
                    ExactRationalInequality(columns, List(columns.size) { BigFraction.MINUS_ONE },
                        BigFraction.MINUS_ONE),
                )
                val budget = SourceLpBudget(maxOperations = 1)
                var support: IntArray? = null

                val point = completeMixedRealComponents(3, rows, budget, Cancellation.Never,
                    onInfeasible = { support = it }, operationCeiling = 0)

                assertNull(point)
                assertEquals(setOf(0, 1), assertNotNull(support).toSet())
                assertEquals(0, budget.operations)
            }
        }
    }

    @Test
    fun `cancelled cheap conflicts should withhold support at the partial ceiling`() {
        for (kind in listOf("graph", "parallel")) {
            val columns = if (kind == "parallel") intArrayOf(0, 1, 2) else intArrayOf(0)
            val rows = listOf(
                ExactRationalInequality(columns, List(columns.size) { BigFraction.ONE }, BigFraction.ZERO),
                ExactRationalInequality(columns, List(columns.size) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            )
            val budget = SourceLpBudget(maxOperations = 1)
            val cancelledStage = if (kind == "parallel") {
                "RECOVERY_REAL_COMPONENT_PARALLEL_CONFLICT"
            } else {
                "RECOVERY_REAL_COMPONENT_GRAPH_CONFLICT"
            }
            var stopped = false
            var support: IntArray? = null

            val point = completeMixedRealComponents(3, rows, budget, Cancellation { stopped },
                onStage = { if (it == cancelledStage) stopped = true },
                onInfeasible = { support = it }, operationCeiling = 0)

            assertTrue(stopped)
            assertNull(point)
            assertNull(support)
            assertEquals(0, budget.operations)
        }
    }

    @Test
    fun `graph conflicts should cite their original component rows`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(3), listOf(BigFraction.ONE), BigFraction.ofLong(2)),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(1, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict),
            )
            val budget = SourceLpBudget(maxOperations = 0)
            var support: IntArray? = null

            val point = completeMixedRealComponents(4, rows, budget, Cancellation.Never,
                onInfeasible = { support = it })

            assertNull(point)
            assertEquals(setOf(1, 2, 3), assertNotNull(support).toSet())
            assertEquals(0, budget.operations)
        }
    }

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
        for (kind in listOf("graph", "parallel", "lp")) {
            val parallel = kind == "parallel"
            val rows = if (kind == "graph") listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            ) else listOf(
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
                ExactRationalInequality(if (parallel) intArrayOf(0, 1, 2) else intArrayOf(0, 1),
                    List(if (parallel) 3 else 2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
                ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            )
            var stopped = false
            var support: IntArray? = null
            val cancelledStage = when (kind) {
                "graph" -> "RECOVERY_REAL_COMPONENT_GRAPH_CONFLICT"
                "parallel" -> "RECOVERY_REAL_COMPONENT_PARALLEL_CONFLICT"
                else -> "RECOVERY_REAL_COMPONENT_LP_INFEASIBLE"
            }

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
