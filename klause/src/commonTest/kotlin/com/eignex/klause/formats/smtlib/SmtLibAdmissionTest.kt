package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibAdmissionTest {
    @Test
    fun `linear sources retain one streaming parse`() {
        var opens = 0

        val input = SmtLibAdmission.parse({
            opens++
            StringCharSource("(declare-const x Real) (assert (< x 1)) (check-sat)")
        })

        assertIs<SmtLibInput.Linear>(input)
        assertEquals(1, opens)
    }

    @Test
    fun `unused real helpers retain one linear streaming parse`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Real (+ x 1))",
            "(define-fun f ((x Real)) Real (* x x))",
            "(define-fun f () Real 3)",
        )) {
            var opens = 0

            val input = SmtLibAdmission.parse({
                opens++
                StringCharSource("$definition (declare-const x Real) (assert (= (* (+ 1 1) x) 6)) (check-sat)")
            })

            val source = assertIs<SmtLibInput.Linear>(input).problem
            assertEquals(mapOf("x" to 0), source.realVarNames)
            assertEquals(1, opens)
        }
    }

    @Test
    fun `unused helper definitions retain lexical sort validation`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Bool (+ x 1))",
            "(define-fun f ((x Real)) Real (> x 1))",
            "(define-fun f () Bool 3)",
        )) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLibAdmission.parse("$definition (declare-const x Real) (assert (= x 1)) (check-sat)")
            }
        }
    }

    @Test
    fun `nested and zero arity helper references retain lexical fallback`() {
        for ((definition, assertion) in listOf(
            "(define-fun f ((y Real)) Real (+ y 1))" to "(= (f x) 2)",
            "(define-fun f ((y Real)) Real (+ y 1)) (define-fun g ((y Real)) Real (f y))" to
                "(or false (= (g x) 2))",
            "(define-fun f () Real 2)" to "(= x f)",
        )) {
            var opens = 0

            val input = SmtLibAdmission.parse({
                opens++
                StringCharSource("(declare-const x Real) $definition (assert $assertion) (check-sat)")
            })

            val source = assertIs<SmtLibInput.Polynomial>(input).problem
            assertEquals(mapOf("x" to 0), source.realVarNames)
            assertEquals(2, opens)
        }
    }

    @Test
    fun `unused nonlinear helpers preserve nonincremental session rejection`() {
        for (suffix in listOf("(check-sat) (check-sat)", "(check-sat) (assert (= x 2))")) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLibAdmission.parse(
                    "(define-fun f ((y Real)) Real (* y y)) " +
                        "(declare-const x Real) (assert (= x 1)) $suffix",
                )
            }
        }
    }

    @Test
    fun `cancellation after source exhaustion withholds unused helper admission`() {
        var exhausted = false
        var opens = 0
        val cancellation = Cancellation { exhausted }

        val input = SmtLibAdmission.parse(
            {
                opens++
                val source = StringCharSource(
                    "(define-fun f ((y Real)) Real (+ y 1)) " +
                        "(declare-const x Real) (assert (= x 2)) (check-sat)",
                )
                object : CharSource {
                    override fun next(): String? = source.next().also { if (it == null) exhausted = true }
                }
            },
            cancellation = cancellation,
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
    }

    @Test
    fun `polynomial fallback closes its rejected source before reopening`() {
        val text = "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)"
        var opens = 0
        var firstClosed = false

        val input = SmtLibAdmission.parse({
            opens++
            if (opens == 1) {
                val source = StringCharSource(text)
                object : CharSource, AutoCloseable {
                    override fun next(): String? = source.next()
                    override fun close() { firstClosed = true }
                }
            } else {
                assertTrue(firstClosed)
                StringCharSource(text)
            }
        })

        assertIs<SmtLibInput.Polynomial>(input)
        assertEquals(2, opens)
    }

    @Test
    fun `fallback drains a source without a close contract before reopening`() {
        var opens = 0
        var firstExhausted = false

        val input = SmtLibAdmission.parse({
            opens++
            if (opens == 1) {
                val chunks = ArrayDeque(
                    listOf("(declare-const x Real) (assert (= (* x x) 2))", "(check-sat)"),
                )
                object : CharSource {
                    override fun next(): String? = chunks.removeFirstOrNull().also {
                        if (it == null) firstExhausted = true
                    }
                }
            } else {
                assertTrue(firstExhausted)
                StringCharSource("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
            }
        })

        assertIs<SmtLibInput.Polynomial>(input)
    }

    @Test
    fun `pre cancelled admission does not open a source`() {
        val input = SmtLibAdmission.parse(
            { error("cancelled admission must not open a source") },
            cancellation = Cancellation { true },
        )

        assertEquals(SmtLibInput.Interrupted, input)
    }

    @Test
    fun `interrupted admission closes its source without retrying`() {
        var stopped = false
        var closed = false
        var opens = 0

        val input = SmtLibAdmission.parse(
            {
                opens++
                object : CharSource, AutoCloseable {
                    override fun next(): String? {
                        stopped = true
                        return "(declare-const x Real)"
                    }
                    override fun close() { closed = true }
                }
            },
            cancellation = Cancellation { stopped },
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
        assertTrue(closed)
    }

    @Test
    fun `interrupted admission does not drain a non closeable tail`() {
        var reads = 0
        var stopped = false
        var opens = 0

        val input = SmtLibAdmission.parse(
            {
                opens++
                object : CharSource {
                    override fun next(): String? {
                        reads++
                        stopped = true
                        return "(declare-const x Real)"
                    }
                }
            },
            cancellation = Cancellation { stopped },
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(1, opens)
        assertEquals(1, reads)
    }

    @Test
    fun `work exhaustion in lexical fallback closes the source without publishing metadata`() {
        var opens = 0
        var closes = 0
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = opens == 2 && charged >= 1
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val input = SmtLibAdmission.parse(
            {
                opens++
                val source = StringCharSource(
                    "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                        "(assert (let ((x 7)) (= (f (+ 1 1)) 14))) (check-sat)",
                )
                object : CharSource, AutoCloseable {
                    override fun next(): String? = source.next()
                    override fun close() { closes++ }
                }
            },
            cancellation = cancellation,
        )

        assertEquals(SmtLibInput.Interrupted, input)
        assertEquals(2, opens)
        assertEquals(2, closes)
        assertEquals(1L, charged)
    }

    @Test
    fun `lexical fallback rejects an assertion using a later helper`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLibAdmission.parse(
                "(assert (= (f 1) 1)) (define-fun f ((x Real)) Real x) (check-sat)",
            )
        }
    }

    @Test
    fun `integer helper sources retain the linear route`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Int) (define-fun f ((y Int)) Int (+ y 1)) (assert (= (f x) 2)) (check-sat)",
        )

        assertIs<SmtLibInput.Linear>(input)
    }

    @Test
    fun `integer helper sorts without integer declarations retain linear admission`() {
        val input = SmtLibAdmission.parse(
            "(define-fun f ((n Int)) Int (+ n 1)) (declare-const x Real) " +
                "(assert (= x (to_real (f 2)))) (check-sat)",
        )

        assertIs<SmtLibInput.Linear>(input)
    }

    @Test
    fun `nonlinear mixed integer sources retain their integer coordinate semantics`() {
        val input = SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const n Int) (assert (= (* x x) (to_real n))) (check-sat)",
        )

        val source = assertIs<SmtLibInput.NonlinearInteger>(input).problem
        assertEquals(mapOf("n" to 0), source.intVarNames)
        assertEquals(mapOf("x" to 0), source.realVarNames)
    }
    @Test
    fun `nonlinear finite sessions reject source changes after a satisfaction query`() {
        val declaration = "(declare-const n Int) (assert (<= 1 n 3)) "
        val suffixes = listOf(
            "(assert (= (* n n) 4)) (check-sat) (assert (= n 3))",
            "(assert (= (* n n) 4)) (check-sat) (check-sat)",
            "(assert (= (^ n 0) 1)) (exit) (assert false)",
        )

        for (suffix in suffixes) {
            assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(declaration + suffix) }
        }
    }

    @Test
    fun `bounded nonlinear sources do not reinterpret signed numeral symbols`() {
        val source = "(declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) -2)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

    @Test
    fun `nonlinear assertion scope excludes later source declarations`() {
        val source = "(assert (= (* x y) 6)) (declare-const x Int) (declare-const y Int) " +
            "(assert (and (<= 1 x 3) (<= 1 y 3)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

    @Test
    fun `nonlinear declarations preserve canonical quoted name identity`() {
        val source = "(declare-const x Int) (declare-const |x| Int) " +
            "(assert (and (<= 1 x 3) (<= 1 |x| 3) (= (* x |x|) 6)))"

        assertFailsWith<UnsupportedSmtException> { SmtLibAdmission.parse(source) }
    }

}
