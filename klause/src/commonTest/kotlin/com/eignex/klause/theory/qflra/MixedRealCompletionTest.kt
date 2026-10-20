package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedRealCompletionTest {
    @Test
    fun `scaled repeated rows should fit the existing source dimension limit`() {
        val rows = List(194) { row ->
            val scale = row + 1L
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(2L * scale), BigFraction.ofLong(scale)), BigFraction.ZERO)
        } + ExactRationalInequality(intArrayOf(61, 128),
            listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        val budget = SourceLpBudget(maxOperations = 1)

        val refuted = refuteRealResidualComponents(130, rows, budget, Cancellation.Never)

        assertTrue(refuted)
        assertEquals(1, budget.operations)
    }

    @Test
    fun `scaled row compaction should preserve strictness at equal endpoints`() {
        for (strict in listOf(false, true)) {
            val rows = List(129) { row ->
                val scale = row + 1L
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ofLong(2L * scale), BigFraction.ofLong(scale)), BigFraction.ZERO,
                    strict = strict && row == 128)
            } + ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.ZERO)

            val refuted = refuteRealResidualComponents(2, rows, SourceLpBudget(), Cancellation.Never)

            assertEquals(strict, refuted)
        }
    }

    @Test
    fun `scaled component points should satisfy every original row and coordinate position`() {
        val rows = List(194) { row ->
            val scale = row + 1L
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(2L * scale), BigFraction.ofLong(scale)), BigFraction.ofLong(2L * scale))
        } + ExactRationalInequality(intArrayOf(61, 128),
            listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)

        val point = completeMixedRealComponents(130, rows, SourceLpBudget(maxOperations = 1), Cancellation.Never)

        assertEquals(130, assertNotNull(point).size)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `cancelled row compaction should withhold a numerical request and refutation`() {
        val rows = List(129) { row ->
            val scale = row + 1L
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(2L * scale), BigFraction.ofLong(scale)), BigFraction.ZERO)
        } + ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        var cancelled = false
        val budget = SourceLpBudget()

        val refuted = refuteRealResidualComponents(2, rows, budget, Cancellation { cancelled },
            onStage = { if (it.startsWith("RECOVERY_REAL_COMPONENT_COMPACT_")) cancelled = true })

        assertFalse(refuted)
        assertEquals(0, budget.operations)
        assertTrue(refuteRealResidualComponents(2, rows, SourceLpBudget(), Cancellation.Never))
    }

    @Test
    fun `surviving real coordinates should fit the existing source query limit`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(2), BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val budget = SourceLpBudget(maxOperations = 1)

        val refuted = refuteRealResidualComponents(130, rows, budget, Cancellation.Never)

        assertTrue(refuted)
        assertEquals(1, budget.operations)
    }

    @Test
    fun `compacted real points should retain their original coordinate positions`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(2), BigFraction.ONE), BigFraction.ofLong(2)),
            ExactRationalInequality(intArrayOf(61, 128),
                listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        val point = completeMixedRealComponents(130, rows, SourceLpBudget(), Cancellation.Never)

        assertEquals(130, assertNotNull(point).size)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `invalid input after a false constant should withhold a component refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ZERO),
        )

        val refuted = refuteRealResidualComponents(2, rows, SourceLpBudget(), Cancellation.Never)

        assertFalse(refuted)
    }

    @Test
    fun `a late component cancellation should withhold a completed refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(2), BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        var cancelled = false

        val refuted = refuteRealResidualComponents(2, rows, SourceLpBudget(), Cancellation { cancelled },
            onStage = { if (it == "PURE_REAL_EQUALITY_GENERAL_REFUTED") cancelled = true })

        assertFalse(refuted)
        assertTrue(refuteRealResidualComponents(2, rows, SourceLpBudget(), Cancellation.Never))
    }

    @Test
    fun `a compact component above source dimensions should withhold a refutation`() {
        val rows = List(126) { row ->
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(2), BigFraction.ofLong(row + 1L)), BigFraction.ZERO)
        } + ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        val budget = SourceLpBudget()

        val refuted = refuteRealResidualComponents(2, rows, budget, Cancellation.Never)

        assertFalse(refuted)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `an infeasible guessed real component should supply no mixed point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(2), BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ofLong(-2), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        val point = completeMixedRealComponents(2, rows, SourceLpBudget(), Cancellation.Never)

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
