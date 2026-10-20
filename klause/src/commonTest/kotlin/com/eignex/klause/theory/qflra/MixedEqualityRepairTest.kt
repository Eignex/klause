package com.eignex.klause.theory.qflra

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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedEqualityRepairTest {
    @Test
    fun `inconsistent rational equalities should return an exact refutation`() {
        for (wide in listOf(false, true)) {
            val scale = if (wide) BigFraction.of(BIG_ONE shl 180, BIG_ONE) else BigFraction.ONE
            val rows = listOf(2L, 3L).flatMap { coefficient ->
                val value = BigFraction.ofLong(coefficient) * scale
                listOf(
                    ExactRationalInequality(intArrayOf(0), listOf(value), BigFraction.ONE),
                    ExactRationalInequality(intArrayOf(0), listOf(value.negated()), BigFraction.MINUS_ONE),
                )
            }

            val result = prepareEqualityRows(1, rows, Cancellation.Never)

            assertEquals(EqualityRows.Refuted, result)
        }
    }

    @Test
    fun `a strict row contradicted by equalities should refute the residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val result = prepared.repair.reduceRows(rows)

        assertEquals(EqualityResidual.Refuted, result)
    }

    @Test
    fun `a closed equality boundary should remain a checked feasible point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val point = prepared.repair.point(List(2) { BigFraction.ZERO })

        assertEquals(listOf(BigFraction.ZERO, BigFraction.ONE), point)
        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
    }

    @Test
    fun `unpaired inequalities should not claim an equality refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        val result = prepareEqualityRows(1, rows, Cancellation.Never)

        assertEquals(EqualityRows.Declined, result)
        assertFalse(equalityRowsRefute(1, rows, Cancellation.Never))
    }

    @Test
    fun `free residual coordinates should retain their source inequalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val point = prepared.repair.completePoint(rows, null)

        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
        assertEquals(point[0], point[1])
        assertTrue(point[1] >= BigFraction.ONE)
    }

    @Test
    fun `an interrupted equality proof should retain work without a refutation`() {
        val rows = listOf(0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        }
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 8L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        var interruptedSupport: IntArray? = null
        var freshSupport: IntArray? = null
        val stopped = equalityRowsRefute(1, rows, stop, onSupport = { interruptedSupport = it })
        val fresh = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { freshSupport = it })

        assertFalse(stopped)
        assertTrue(work >= 8L)
        assertTrue(fresh)
        assertNull(interruptedSupport)
        assertEquals(setOf(0, 1, 2, 3), assertNotNull(freshSupport).toSet())
    }

    @Test
    fun `strict residual support should exclude unrelated inequalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ofLong(4)),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(0, 1, 2, 3, 4), indices.toSet())
        assertTrue(equalityRowsRefute(3, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `inconsistent equation support should retain both original directions`() {
        val rows = listOf(0L, 0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        }
        var support: IntArray? = null

        val refuted = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        assertEquals(setOf(0, 1, 4, 5), assertNotNull(support).toSet())
    }

    @Test
    fun `invalid input after contradictory equations should withhold support`() {
        val rows = listOf(0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        } + ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO)
        var support: IntArray? = null

        val refuted = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
    }

    @Test
    fun `invalid source coordinate aliases should withhold an equality proof`() {
        for (invalid in listOf(-1, 0, 2)) {
            val row = ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ZERO)
            row.columns[1] = invalid

            val result = prepareEqualityRows(2, listOf(row), Cancellation.Never)

            assertEquals(EqualityRows.Declined, result)
        }
    }

    @Test
    fun `equality admission limits should withhold proof authority`() {
        val huge = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)
        val cases = listOf(
            257 to emptyList(),
            1 to listOf(ExactRationalInequality(intArrayOf(0), listOf(huge), BigFraction.ONE)),
        )
        for ((columns, rows) in cases) {
            val result = prepareEqualityRows(columns, rows, Cancellation.Never)

            assertEquals(EqualityRows.Declined, result)
            assertFalse(equalityRowsRefute(columns, rows, Cancellation.Never))
        }
    }

    @Test
    fun `a failed rounded integer trial should leave a feasible sibling available`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2, 3),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
        )

        val failed = completeMixedPoint(3, 1, doubleArrayOf(1.0, 0.0, 0.0, 0.0), rows, Cancellation.Never)
        val sibling = completeMixedPoint(3, 1, doubleArrayOf(1.0, 0.0, 0.0, 1.0), rows, Cancellation.Never)

        assertNull(failed)
        assertTrue(assertNotNull(sibling).satisfiesSourceRows(rows))
    }
}
