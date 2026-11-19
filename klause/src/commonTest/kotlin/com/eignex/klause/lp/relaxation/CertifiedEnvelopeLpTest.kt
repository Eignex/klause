package com.eignex.klause.lp.relaxation

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertifiedEnvelopeLpTest {
    @Test
    fun `an appended affine relation should preserve strict feasible proposals`() {
        val bounds = listOf(
            EnvelopeInterval(BigFraction.ZERO, BigFraction.ofLong(2)),
            EnvelopeInterval(BigFraction.ZERO, BigFraction.ONE),
            EnvelopeInterval(BigFraction.ZERO, BigFraction.ofLong(3)),
        )
        val equality = listOf(0 to BigFraction.ONE, 1 to BigFraction.ONE, 2 to BigFraction.MINUS_ONE)

        val point = requireNotNull(CertifiedEnvelopeLp.prepare(bounds, emptyList(), ExecutionControl.Never)).use { lp ->
            assertTrue(lp.append(listOf(
                envelopeRow(listOf(0 to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE, strict = true),
                envelopeRow(equality, BigFraction.ZERO),
                envelopeRow(equality.map { it.first to it.second.negated() }, BigFraction.ZERO),
            )))
            requireNotNull(lp.solve()?.exactPrimal)
        }

        assertTrue(point[0] > BigFraction.ONE)
        assertEquals(point[0] + point[1], point[2])
    }

    @Test
    fun `an infeasible endpoint trial should preserve the opposite objective`() {
        val rows = listOf(
            envelopeRow(listOf(0 to BigFraction.MINUS_ONE, 1 to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            envelopeRow(listOf(0 to BigFraction.MINUS_ONE, 1 to BigFraction.ONE), BigFraction.ZERO),
        )
        val bounds = List(2) { EnvelopeInterval(BigFraction.ZERO, BigFraction.ONE) }

        requireNotNull(CertifiedEnvelopeLp.prepare(bounds, rows, ExecutionControl.Never)).use { lp ->
            val minimum = requireNotNull(lp.solve(0))
            val maximum = requireNotNull(lp.solve(0, upper = true))

            assertEquals(BigFraction.ofLong(2).reciprocal(), minimum.lowerBound)
            assertEquals(BigFraction.MINUS_ONE, maximum.lowerBound)
        }
    }
}
