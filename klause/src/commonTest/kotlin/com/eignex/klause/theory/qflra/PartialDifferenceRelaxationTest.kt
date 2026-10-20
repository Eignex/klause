package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialDifferenceRelaxationTest {
    @Test
    fun `scaled differences should retain their exact rational separation`() {
        for (coefficient in listOf(3L, -3L)) {
            val comparison = ExactComparison(
                mapOf(0 to BigFraction.ofLong(coefficient), 1 to BigFraction.ofLong(-coefficient)),
                BigFraction.ONE, LinearOp.EQ, false, false,
            )
            val relaxation = assertNotNull(
                PartialDifferenceRelaxation.prepare(2, listOf(comparison), Cancellation.Never),
            )

            val point = assertIs<PartialDifferenceRelaxation.Result.Feasible>(
                relaxation.check(Cancellation.Never),
            ).point

            assertEquals(BigFraction.ofLong(coefficient).reciprocal(), point[0] - point[1])
        }
    }

    @Test
    fun `strict integer expressions should retain a fractional relaxation interior`() {
        val comparisons = listOf(
            ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ZERO, LinearOp.GE, true, false),
            ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ONE, LinearOp.LE, true, false),
        )
        val relaxation = assertNotNull(PartialDifferenceRelaxation.prepare(1, comparisons, Cancellation.Never))

        val point = assertIs<PartialDifferenceRelaxation.Result.Feasible>(
            relaxation.check(Cancellation.Never),
        ).point.single()

        assertTrue(point > BigFraction.ZERO)
        assertTrue(point < BigFraction.ONE)
    }

    @Test
    fun `an unsupported expression should decline complete prefix coverage`() {
        for (op in listOf(LinearOp.LE, LinearOp.NE)) {
            for (terms in listOf(
                mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE),
                mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE, 2 to BigFraction.ONE),
                mapOf(3 to BigFraction.ONE),
            )) {
                val comparisons = listOf(
                    ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ZERO, LinearOp.GE, false, false),
                    ExactComparison(terms, BigFraction.ONE, op, false, false),
                )

                assertNull(PartialDifferenceRelaxation.prepare(3, comparisons, Cancellation.Never))
            }
        }
    }

    @Test
    fun `a graph point must satisfy every disequality without a private split`() {
        for (bound in listOf(BigFraction.ZERO, BigFraction.ONE)) {
            val comparisons = listOf(
                ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ZERO, LinearOp.EQ, false, false),
                ExactComparison(mapOf(0 to BigFraction.ONE), bound, LinearOp.NE, false, false),
            )
            val relaxation = assertNotNull(PartialDifferenceRelaxation.prepare(1, comparisons, Cancellation.Never))

            val result = relaxation.check(Cancellation.Never)

            if (bound.isZero) assertIs<PartialDifferenceRelaxation.Result.Declined>(result)
            else assertIs<PartialDifferenceRelaxation.Result.Feasible>(result)
        }
    }

    @Test
    fun `incompatible bounds should decline without exporting a conflict`() {
        val comparisons = listOf(
            ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ONE, LinearOp.GE, false, false),
            ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ZERO, LinearOp.LE, false, false),
        )
        val relaxation = assertNotNull(PartialDifferenceRelaxation.prepare(1, comparisons, Cancellation.Never))

        assertIs<PartialDifferenceRelaxation.Result.Declined>(relaxation.check(Cancellation.Never))
    }

    @Test
    fun `interrupted prefix work should retain its containing charge`() {
        val comparisons = listOf(
            ExactComparison(mapOf(0 to BigFraction.ONE), BigFraction.ZERO, LinearOp.EQ, false, false),
        )
        val relaxation = assertNotNull(PartialDifferenceRelaxation.prepare(1, comparisons, Cancellation.Never))
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter(): WorkMeter = meter
        }

        assertIs<PartialDifferenceRelaxation.Result.Interrupted>(relaxation.check(stop))
        val spent = charged
        assertTrue(spent > 0L)
        assertNull(PartialDifferenceRelaxation.prepare(1, comparisons, stop))
        assertEquals(spent, charged)
    }
}
