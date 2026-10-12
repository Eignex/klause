package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArithmeticPublicationTest {
    @Test
    fun `a failed stdout stream aborts actual protocol publication`() {
        val output = SmtLibOutput()
        val publication = ArithmeticPublication(output)
        val previous = System.out
        val failed = PrintStream(object : OutputStream() {
            override fun write(b: Int) { throw IOException("closed consumer") }
        })

        System.setOut(failed)
        try {
            assertFailsWith<IllegalStateException> {
                publication.publish("(\n  (define-fun x () Int 1)\n)", SourceValue.Rational(BigFraction.ONE))
            }
        } finally {
            System.setOut(previous)
            failed.close()
        }

        assertEquals(0L, publication.solutions)
    }

    @Test
    fun `two accepted incumbents reach the consumer before terminal return`() {
        val received = mutableListOf<Pair<String, SourceValue>>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                received.add(rendered to objective)
                return true
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
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                received.add(rendered)
                return true
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
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
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
    fun `a source renderer veto is not counted or retained as a publication`() {
        var permitted = false
        var emitted = 0
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                if (!permitted) return false
                emitted++
                return true
            }
        }
        val publication = ArithmeticPublication(output)
        val objective = SourceValue.Rational(BigFraction.ONE)

        publication.publish("x=1", objective)
        assertEquals(0L, publication.solutions)
        permitted = true
        publication.publish("x=1", objective)

        assertEquals(1, emitted)
        assertEquals(1L, publication.solutions)
    }

    @Test
    fun `separate invocations each publish their own first incumbent`() {
        val received = mutableListOf<String>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                received.add(rendered)
                return true
            }
        }

        ArithmeticPublication(output).publish("x=1", SourceValue.Rational(BigFraction.ONE))
        ArithmeticPublication(output).publish("x=1", SourceValue.Rational(BigFraction.ONE))

        assertEquals(listOf("x=1", "x=1"), received)
    }
}
