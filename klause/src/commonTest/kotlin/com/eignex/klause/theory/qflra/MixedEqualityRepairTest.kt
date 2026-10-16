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
    fun `equality support should preserve the first actual opposite pair`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ofLong(5)),
        )

        val result = assertIs<SourceEqualityProof.Refuted>(refuteCapturedSourceEqualities(2, rows, Cancellation.Never))

        val used = mutableSetOf<Int>()
        assertTrue(result.support.visit(Cancellation.Never) { used.add(it); true })
        assertEquals(setOf(0, 2, 4, 5), used)
    }

    @Test
    fun `residual support should include used pivots and exclude zero coefficient pivots`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ofLong(5)),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-5)),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ofLong(2)), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ofLong(-2)), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(2)),
                BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1, 2), listOf(BigFraction.MINUS_ONE, BigFraction.ZERO),
                BigFraction.MINUS_ONE),
        )

        val result = assertIs<SourceEqualityProof.Refuted>(refuteCapturedSourceEqualities(3, rows, Cancellation.Never))

        val used = mutableSetOf<Int>()
        assertTrue(result.support.visit(Cancellation.Never) { used.add(it); true })
        assertEquals(setOf(2, 3, 4, 5, 6), used)
    }

    @Test
    fun `a strict endpoint refutation should include its own captured row`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict),
            )

            val result = refuteCapturedSourceEqualities(1, rows, Cancellation.Never)

            if (strict) {
                val used = mutableSetOf<Int>()
                assertTrue(assertIs<SourceEqualityProof.Refuted>(result).support.visit(Cancellation.Never) {
                    used.add(it)
                    true
                })
                assertEquals(setOf(0, 1, 2), used)
            } else {
                assertIs<SourceEqualityProof.Declined>(result)
            }
        }
    }

    @Test
    fun `caller mutations should not replace the captured equality catalogue`() {
        val columns = intArrayOf(0)
        val coefficients = mutableListOf(BigFraction.ONE)
        val rows = mutableListOf(
            ExactRationalInequality(columns, coefficients, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict = true),
        )

        val result = refuteCapturedSourceEqualities(1, rows, Cancellation.Never) { stage ->
            if (stage == "RECOVERY_SUPPORT_CAPTURED") {
                columns[0] = 4
                coefficients.clear()
                rows.clear()
            }
        }

        val used = mutableSetOf<Int>()
        assertTrue(assertIs<SourceEqualityProof.Refuted>(result).support.visit(Cancellation.Never) {
            used.add(it)
            true
        })
        assertEquals(setOf(0, 1, 2), used)
    }

    @Test
    fun `the live output support should fit the same capture boundary`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO, strict = true),
        )

        val admitted = refuteCapturedSourceEqualities(1, rows, Cancellation.Never, captureEntries = 65269L)
        val declined = refuteCapturedSourceEqualities(1, rows, Cancellation.Never, captureEntries = 65270L)

        assertIs<SourceEqualityProof.Refuted>(admitted)
        assertIs<SourceEqualityProof.Unavailable>(declined)
    }

    @Test
    fun `an interrupted support operation should withhold its proof and permit a live retry`() {
        for (interrupted in listOf("RECOVERY_SUPPORT_CAPTURED", "RECOVERY_SUPPORT_UNION", "RECOVERY_SUPPORT_OUTPUT")) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            )
            var stopped = false

            val result = refuteCapturedSourceEqualities(1, rows, Cancellation { stopped }) { stage ->
                if (stage == interrupted) stopped = true
            }

            assertTrue(stopped)
            assertIs<SourceEqualityProof.Declined>(result)
            assertIs<SourceEqualityProof.Refuted>(refuteCapturedSourceEqualities(1, rows, Cancellation.Never))
        }
    }

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
