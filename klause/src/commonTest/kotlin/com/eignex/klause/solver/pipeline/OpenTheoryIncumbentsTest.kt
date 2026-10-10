package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.parseBigInt
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

    private fun value(text: String): BigInt = parseBigInt(text)

    @Test
    fun `an external integer bound preserves precision beyond Double and Long`() {
        for (text in listOf("9007199254740993", "-9007199254740993", "9223372036854775808")) {
            val exchange = IncumbentExchange<OpenTheoryAssignment, BigFraction>(
                improves = { candidate, standing -> candidate < standing },
            )
            val bound = value(text)
            exchange.offer(witness(0), BigFraction.of(bound, BIG_ONE))

            assertEquals(bound, exchange.integerBound(), text)
        }
    }

    @Test
    fun `a fractional incumbent supplies no integer cutoff`() {
        val exchange = IncumbentExchange<OpenTheoryAssignment, BigFraction>(
            improves = { candidate, standing -> candidate < standing },
        )
        exchange.offer(witness(0), BigFraction.of(value("3"), value("2")))

        assertNull(exchange.integerBound())
    }

    @Test
    fun `a strictly lower value replaces the standing incumbent`() {
        val exchange = minimizingWitnessExchange()
        exchange.offer(witness(7), value("7"))
        val better = witness(3)

        assertIs<Publication.Installed<OpenTheoryAssignment, BigInt>>(exchange.offer(better, value("3")))

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
}
