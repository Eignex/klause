package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArithmeticPublicationTest {
    @Test
    fun `two accepted incumbents reach the consumer before terminal return`() {
        val received = mutableListOf<Pair<String, SourceValue>>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue) {
                received.add(rendered to objective)
            }
        }
        val publication = ArithmeticPublication(output)
        val first = SourceValue.Rational(BigFraction.ofLong(3))
        val second = SourceValue.Rational(BigFraction.ofLong(2))

        publication.publish("x=3", first)
        assertEquals(listOf("x=3" to first), received)
        publication.publish("x=2", second)

        assertEquals(listOf("x=3" to first, "x=2" to second), received)
        assertEquals(2L, publication.solutions)
    }

    @Test
    fun `repeating the final assignment and objective emits no duplicate`() {
        val received = mutableListOf<String>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue) {
                received.add(rendered)
            }
        }
        val publication = ArithmeticPublication(output)

        publication.publish("x=2", SourceValue.Rational(BigFraction.ofLong(2)))
        publication.publish("x=2", SourceValue.Rational(BigFraction.ofLong(2)))

        assertEquals(listOf("x=2"), received)
        assertEquals(1L, publication.solutions)
    }

    @Test
    fun `consumer failure propagates without recording successful publication`() {
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue) {
                error("closed output")
            }
        }
        val publication = ArithmeticPublication(output)

        assertFailsWith<IllegalStateException> {
            publication.publish("x=1", SourceValue.Rational(BigFraction.ONE))
        }

        assertEquals(0L, publication.solutions)
    }

    @Test
    fun `separate invocations each publish their own first incumbent`() {
        val received = mutableListOf<String>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue) {
                received.add(rendered)
            }
        }

        ArithmeticPublication(output).publish("x=1", SourceValue.Rational(BigFraction.ONE))
        ArithmeticPublication(output).publish("x=1", SourceValue.Rational(BigFraction.ONE))

        assertEquals(listOf("x=1", "x=1"), received)
    }
}
