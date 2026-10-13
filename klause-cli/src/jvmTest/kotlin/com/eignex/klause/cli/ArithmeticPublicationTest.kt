package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ArithmeticPublicationTest {
    @Test
    fun `a consumer failure aborts actual source publication and leaves a later invocation usable`() {
        val start = System.nanoTime()
        val source = File.createTempFile("quadratic-output-failure", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= x 1)) (minimize (* x x)) (check-sat)")
        }
        val common = CommonOptions().apply { engine = "fixed"; parallel = 1 }
        val session = SmtLibMode.newSession()
        val solvable = session.load(source.absolutePath, common)
        val loaded = System.nanoTime()
        val expected = IOException("closed consumer")
        var terminal = false
        val failed = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertEquals(false, terminal)
                throw expected
            }
            override fun onVerdictContext(context: VerdictContext) { terminal = true }
        }
        var emitted = 0
        var completed: Verdict? = null
        val healthy = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                emitted++
                return true
            }
            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
                assertEquals(null, rendered)
            }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        try {
            assertSame(expected, assertFailsWith<IOException> { SolveCore.solve(solvable, common, failed) })
            val failedAt = System.nanoTime()
            SolveCore.solve(solvable, CommonOptions().apply { engine = "fixed"; parallel = 1 }, healthy)
            println("publication cleanup ns load=${loaded - start} failure=${failedAt - loaded} " +
                "healthy=${System.nanoTime() - failedAt}")
        } finally {
            source.delete()
        }

        assertEquals(false, terminal)
        assertEquals(1, emitted)
        assertEquals(Verdict.OPTIMAL, completed)
    }

    @Test
    fun `an accepted identity suppresses a final source-owned equivalent representation`() {
        var emitted = 0
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                emitted++
                return true
            }
        }
        val publication = ArithmeticPublication(output)
        val first = object : SourceValue.Extension {}
        val equivalent = object : SourceValue.Extension {}

        publication.publish("first source representation", first, acceptedVersion = 1L)
        publication.publish("equivalent final representation", equivalent, acceptedVersion = 1L)

        assertEquals(1, emitted)
        assertEquals(1L, publication.solutions)
    }

    @Test
    fun `source refinement can veto a repeated accepted identity`() {
        var permitted = true
        var emitted = 0
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun arithmeticSourceValue(objective: SourceValue): SourceValue? =
                if (permitted) objective else null

            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                emitted++
                return true
            }
        }
        val publication = ArithmeticPublication(output)
        val value = SourceValue.Rational(BigFraction.ONE)
        publication.publish("x=1", value, acceptedVersion = 1L)
        permitted = false

        val accepted = publication.publish("x=1", value, acceptedVersion = 1L)

        assertEquals(false, accepted)
        assertEquals(1, emitted)
        assertEquals(1L, publication.solutions)
    }

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
        val first: SourceValue = SourceValue.Rational(BigFraction.ofLong(3))
        val second: SourceValue = SourceValue.Rational(BigFraction.ofLong(2))

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
