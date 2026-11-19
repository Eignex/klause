package com.eignex.klause.lp.relaxation

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertifiedEnvelopeLpTest {
    @Test
    fun `disconnected affine blocks should complete within caller funding`() {
        val blocks = 24
        val bounds = List(blocks * 2) { EnvelopeInterval(BigFraction.ZERO, BigFraction.ONE) }
        val rows = (0 until blocks).flatMap { block ->
            val terms = listOf(block * 2 to BigFraction.ONE, block * 2 + 1 to BigFraction.ONE)
            listOf(envelopeRow(terms, BigFraction.ONE),
                envelopeRow(terms.map { it.first to it.second.negated() }, BigFraction.MINUS_ONE))
        }
        var charged = 0L
        val control = ExecutionControl({ charged >= 40_000L }, WorkMeter { charged += it })

        val point = requireNotNull(CertifiedEnvelopeLp.prepare(bounds, rows, control)).use { lp ->
            requireNotNull(lp.solve()?.exactPrimal)
        }

        for (block in 0 until blocks) assertEquals(BigFraction.ONE, point[block * 2] + point[block * 2 + 1])
        assertTrue(charged < 40_000L)
    }

    @Test
    fun `a relaxing coordinate direction should publish no finite bound`() {
        val bounds = listOf(EnvelopeInterval(BigFraction.ZERO), EnvelopeInterval(BigFraction.ZERO, BigFraction.ONE))
        val row = envelopeRow(listOf(0 to BigFraction.MINUS_ONE, 1 to BigFraction.ONE), BigFraction.ZERO)

        requireNotNull(CertifiedEnvelopeLp.prepare(bounds, listOf(row), ExecutionControl.Never)).use { lp ->
            val maximum = lp.solve(0, upper = true)
            val minimum = requireNotNull(lp.solve(0))

            assertNull(maximum?.lowerBound)
            assertEquals(BigFraction.ZERO, minimum.lowerBound)
        }
    }

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
    fun `an endpoint trial should preserve the opposite objective`() {
        val bounds = listOf(EnvelopeInterval(BigFraction.ZERO, BigFraction.ONE))

        requireNotNull(CertifiedEnvelopeLp.prepare(bounds, emptyList(), ExecutionControl.Never)).use { lp ->
            val minimum = requireNotNull(lp.solve(0))
            val maximum = requireNotNull(lp.solve(0, upper = true))

            assertEquals(BigFraction.ZERO, minimum.lowerBound)
            assertEquals(BigFraction.MINUS_ONE, maximum.lowerBound)
        }
    }
}
