package com.eignex.klause.lp.relaxation

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class CertifiedEnvelopeLpTest {
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
