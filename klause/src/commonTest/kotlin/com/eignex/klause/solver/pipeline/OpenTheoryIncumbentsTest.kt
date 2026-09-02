package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Publication
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * An exact witness reaches a descent's incumbent through one strict-improvement gate, ordered by the
 * arbitrary-precision value offered with it.
 */
class OpenTheoryIncumbentsTest {

    private fun witness(x: Long): OpenTheoryAssignment =
        OpenTheoryAssignment.Difference(Sample(BooleanArray(0), longArrayOf(x)))

    private fun value(text: String): BigInteger = BigInteger.parseString(text)

    @Test
    fun `no incumbent stands before the first witness is offered`() {
        assertNull(minimizingWitnessExchange().current())
    }

    @Test
    fun `the first witness offered becomes the incumbent`() {
        val exchange = minimizingWitnessExchange()
        val first = witness(7)

        val published = assertIs<Publication.Installed<OpenTheoryAssignment, BigInteger>>(
            exchange.offer(first, value("7")),
        )

        assertEquals(first, published.incumbent.assignment)
        assertEquals(value("7"), exchange.current()?.objective)
    }

    @Test
    fun `a strictly lower value replaces the standing incumbent`() {
        val exchange = minimizingWitnessExchange()
        exchange.offer(witness(7), value("7"))
        val better = witness(3)

        assertIs<Publication.Installed<OpenTheoryAssignment, BigInteger>>(exchange.offer(better, value("3")))

        assertEquals(better, exchange.current()?.assignment)
        assertEquals(value("3"), exchange.current()?.objective)
    }

    @Test
    fun `each installed improvement advances the incumbent version`() {
        val exchange = minimizingWitnessExchange()

        exchange.offer(witness(7), value("7"))
        exchange.offer(witness(3), value("3"))

        assertEquals(2L, exchange.current()?.version)
    }

    @Test
    fun `a witness repeating the standing value is not installed`() {
        val exchange = minimizingWitnessExchange()
        exchange.offer(witness(7), value("7"))

        assertEquals(Publication.NotImproving, exchange.offer(witness(7), value("7")))
    }

    @Test
    fun `a witness above the standing value is not installed`() {
        val exchange = minimizingWitnessExchange()
        exchange.offer(witness(3), value("3"))

        assertEquals(Publication.NotImproving, exchange.offer(witness(9), value("9")))
        assertEquals(value("3"), exchange.current()?.objective)
    }

    @Test
    fun `an improvement wider than a Long keeps its precision`() {
        val exchange = minimizingWitnessExchange()
        val huge = value("170141183460469231731687303715884105728")
        exchange.offer(witness(0), huge)

        assertIs<Publication.Installed<OpenTheoryAssignment, BigInteger>>(
            exchange.offer(witness(1), huge - BigInteger.ONE),
        )

        assertEquals(huge - BigInteger.ONE, exchange.current()?.objective)
    }
}
