package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedEqualityRepairTest {
    @Test
    fun `incompatible equalities should have an explicit refutation result`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )

        val result = prepareSourceEqualities(2, rows, Cancellation.Never)

        assertIs<SourceEqualityPreparation.Refuted>(result)
        assertNull(prepareMixedEqualityRepair(2, rows, Cancellation.Never))
    }

    @Test
    fun `compatible dependent equalities should leave no contradictory residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(2) }, BigFraction.ofLong(2)),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(-2) }, BigFraction.ofLong(-2)),
        )
        val prepared = assertIs<SourceEqualityPreparation.Ready>(prepareSourceEqualities(2, rows, Cancellation.Never))

        val result = prepared.repair.checkedResidual(rows)

        assertTrue(assertIs<SourceEqualityResidual.Rows>(result).rows.isEmpty())
    }

    @Test
    fun `a strict residual should reject an equality endpoint`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict),
            )
            val prepared = assertIs<SourceEqualityPreparation.Ready>(
                prepareSourceEqualities(1, rows, Cancellation.Never),
            )

            val result = prepared.repair.checkedResidual(rows)

            if (strict) {
                assertIs<SourceEqualityResidual.Refuted>(result)
            } else {
                assertIs<SourceEqualityResidual.Rows>(result)
            }
        }
    }

    @Test
    fun `equality substitution should refute a false nonstrict residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ofLong(2)), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ofLong(-2)), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(2)),
                BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val prepared = assertIs<SourceEqualityPreparation.Ready>(prepareSourceEqualities(2, rows, Cancellation.Never))

        val result = prepared.repair.checkedResidual(rows)

        assertIs<SourceEqualityResidual.Refuted>(result)
    }

    @Test
    fun `an unfixed real direction should remain a residual obligation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val prepared = assertIs<SourceEqualityPreparation.Ready>(prepareSourceEqualities(2, rows, Cancellation.Never))

        val result = assertIs<SourceEqualityResidual.Rows>(prepared.repair.checkedResidual(rows))

        assertEquals(1, result.rows.size)
    }

    @Test
    fun `differently scaled standalone rows should decline literal equality pairing`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(-2)), BigFraction.ZERO),
        )

        val result = prepareSourceEqualities(1, rows, Cancellation.Never)

        assertIs<SourceEqualityPreparation.Declined>(result)
    }

    @Test
    fun `strict opposite rows should not establish an equality`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict = true),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
        )

        val result = prepareSourceEqualities(1, rows, Cancellation.Never)

        assertIs<SourceEqualityPreparation.Declined>(result)
    }

    @Test
    fun `source equality workspace overflow should decline without a proof`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
        )

        val result = prepareSourceEqualities(257, rows, Cancellation.Never)

        assertIs<SourceEqualityPreparation.Declined>(result)
        assertNull(prepareMixedEqualityRepair(257, rows, Cancellation.Never))
    }

    @Test
    fun `oversized source coefficients should decline equality preparation`() {
        val tiny = BigFraction.of(BIG_ONE, BIG_ONE shl 4097)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(tiny), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(tiny.negated()), BigFraction.ZERO),
        )

        val result = prepareSourceEqualities(1, rows, Cancellation.Never)

        assertIs<SourceEqualityPreparation.Declined>(result)
    }

    @Test
    fun `interrupted elimination should not refute incompatible equalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        var stopped = false

        val result = prepareSourceEqualities(1, rows, Cancellation { stopped }) { stage ->
            if (stage == "RECOVERY_EQUALITY_PIVOT") stopped = true
        }

        assertTrue(stopped)
        assertIs<SourceEqualityPreparation.Declined>(result)
    }

    @Test
    fun `interruption before residual publication should withhold its contradiction`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict = true),
        )
        var stopped = false
        val prepared = assertIs<SourceEqualityPreparation.Ready>(
            prepareSourceEqualities(1, rows, Cancellation { stopped }) { stage ->
                if (stage == "RECOVERY_EQUALITY_RESIDUAL_FALSE") stopped = true
            },
        )

        val result = prepared.repair.checkedResidual(rows)

        assertTrue(stopped)
        assertIs<SourceEqualityResidual.Declined>(result)
    }

    @Test
    fun `residual input outside the prepared coordinates should decline`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
        )
        val prepared = assertIs<SourceEqualityPreparation.Ready>(prepareSourceEqualities(1, rows, Cancellation.Never))
        val residual = listOf(
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.MINUS_ONE),
        )

        val result = prepared.repair.checkedResidual(residual)

        assertIs<SourceEqualityResidual.Declined>(result)
    }

    @Test
    fun `equalities should reconstruct an exact point from free coordinates`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val repair = assertNotNull(prepareMixedEqualityRepair(2, rows, Cancellation.Never))

        val point = repair.point(listOf(BigFraction.ZERO, BigFraction.ZERO))

        assertEquals(listOf(BigFraction.ONE, BigFraction.ZERO), point)
    }
}
