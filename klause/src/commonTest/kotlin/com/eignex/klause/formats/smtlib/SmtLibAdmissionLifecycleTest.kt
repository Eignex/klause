package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.withBoundedPolling
import com.eignex.util.io.CharSource
import com.eignex.util.io.StringCharSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibAdmissionLifecycleTest {
    @Test
    fun `bounded polling should stop admission at its exact work allowance`() {
        val budget = PresolveBudget(1)

        assertFailsWith<CancellationException> {
            SmtLib.parse(
                "(declare-const x Real) (assert (= x 1)) (check-sat)",
                cancellation = budget.slice(1).withBoundedPolling(),
            )
        }

        assertEquals(1L, budget.spent())
    }

    @Test
    fun `a provided closeable source should close when admission is already interrupted`() {
        var reads = 0
        var closes = 0
        val source = object : CharSource, AutoCloseable {
            override fun next(): String? {
                reads++
                return null
            }

            override fun close() {
                closes++
            }
        }

        assertFailsWith<CancellationException> { SmtLib.parse(source, cancellation = ExecutionControl { true }) }

        assertEquals(0, reads)
        assertEquals(1, closes)
    }

    @Test
    fun `a source cancellation exception should not drain a borrowed stream`() {
        var reads = 0
        val source = object : CharSource {
            override fun next(): String? {
                if (++reads == 1) return "(declare-const x Real)"
                throw CancellationException("source stopped")
            }
        }

        assertFailsWith<CancellationException> { SmtLib.parse(source) }

        assertEquals(2, reads)
    }

    @Test
    fun `cancellation during nonlinear bound closure should withhold source admission`() {
        var work = 0L
        var capturedWork: Long? = null
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = capturedWork?.let { work > it } == true
            override val accounting: WorkMeter get() = WorkMeter { work += it }
        }

        val input = runCatching {
            SmtLib.parse(
                {
                    val source = StringCharSource(
                        "(declare-const n Int) (declare-const m Int) (declare-const x Real) " +
                            "(assert (and (>= m 0) (<= m 1) (= n (+ m 1)))) " +
                            "(assert (= (* (to_real n) x) 4)) (check-sat)",
                    )
                    object : CharSource, AutoCloseable {
                        override fun next(): String? = source.next()
                        override fun close() {
                            capturedWork = work
                        }
                    }
                },
                cancellation = stop,
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertTrue(work > requireNotNull(capturedWork))
    }

    @Test
    fun `linear sources should retain one streaming parse`() {
        var opens = 0

        val input = SmtLib.parse({
            opens++
            StringCharSource("(declare-const x Real) (assert (< x 1)) (check-sat)")
        })

        assertIs<Problem>((input).source.originalSource)
        assertEquals(1, opens)
    }

    @Test
    fun `unused real helpers should retain one linear streaming parse`() {
        for (definition in listOf(
            "(define-fun f ((x Real)) Real (+ x 1))",
            "(define-fun f ((x Real)) Real (* x x))",
            "(define-fun f () Real 3)",
        )) {
            var opens = 0

            val input = SmtLib.parse({
                opens++
                StringCharSource("$definition (declare-const x Real) (assert (= (* (+ 1 1) x) 6)) (check-sat)")
            })

            val source = (input)
            assertEquals(mapOf("x" to 0), source.realVarNames)
            assertEquals(1, opens)
        }
    }

    @Test
    fun `cancellation after source exhaustion should withhold unused helper admission`() {
        var exhausted = false
        var opens = 0
        val cancellation = ExecutionControl { exhausted }

        val input = runCatching {
            SmtLib.parse(
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
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
    }

    @Test
    fun `polynomial admission should open and close its source once`() {
        val text = "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)"
        var opens = 0
        var closes = 0

        val input = SmtLib.parse({
            opens++
            check(opens == 1)
            val source = StringCharSource(text)
            object : CharSource, AutoCloseable {
                override fun next(): String? = source.next()
                override fun close() {
                    closes++
                }
            }
        })

        assertEquals(mapOf("x" to 0), input.realVarNames)
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test
    fun `polynomial admission should exhaust its non closeable source once`() {
        var opens = 0
        var exhausted = false

        val input = SmtLib.parse({
            opens++
            check(opens == 1)
            val chunks = ArrayDeque(
                listOf("(declare-const x Real) (assert (= (* x x) 2))", "(check-sat)"),
            )
            object : CharSource {
                override fun next(): String? = chunks.removeFirstOrNull().also {
                    if (it == null) exhausted = true
                }
            }
        })

        assertEquals(mapOf("x" to 0), input.realVarNames)
        assertEquals(1, opens)
        assertTrue(exhausted)
    }

    @Test
    fun `pre cancelled admission should not open a source`() {
        val input = runCatching {
            SmtLib.parse(
                { error("cancelled admission must not open a source") },
                cancellation = ExecutionControl { true },
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
    }

    @Test
    fun `interrupted admission should close its source without retrying`() {
        var stopped = false
        var closed = false
        var opens = 0

        val input = runCatching {
            SmtLib.parse(
                {
                    opens++
                    object : CharSource, AutoCloseable {
                        override fun next(): String? {
                            stopped = true
                            return "(declare-const x Real)"
                        }
                        override fun close() {
                            closed = true
                        }
                    }
                },
                cancellation = ExecutionControl { stopped },
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
        assertTrue(closed)
    }

    @Test
    fun `interrupted admission should not drain a non closeable tail`() {
        var reads = 0
        var stopped = false
        var opens = 0

        val input = runCatching {
            SmtLib.parse(
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
                cancellation = ExecutionControl { stopped },
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
        assertEquals(1, reads)
    }

    @Test
    fun `work exhaustion during source capture should close once without publishing metadata`() {
        var opens = 0
        var closes = 0
        var charged = 0L
        val cancellation = object : ExecutionControl() {
            override fun isCancelled(): Boolean = charged >= 1
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }

        val input = runCatching {
            SmtLib.parse(
                {
                    opens++
                    val source = StringCharSource(
                        "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) " +
                            "(assert (let ((x 7)) (= (f (+ 1 1)) 14))) (check-sat)",
                    )
                    object : CharSource, AutoCloseable {
                        override fun next(): String? = source.next()
                        override fun close() {
                            closes++
                        }
                    }
                },
                cancellation = cancellation,
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
        assertEquals(1, closes)
        assertEquals(1L, charged)
    }

    @Test
    fun `polynomial admission should retain the original commands when a factory cannot replay them`() {
        var opens = 0

        val input = SmtLib.parse({
            opens++
            StringCharSource(if (opens == 1) {
                "(declare-const x Real) (assert (= (* x x) 2)) (check-sat)"
            } else "(assert false) (check-sat)")
        })

        assertEquals(mapOf("x" to 0), input.realVarNames)
        assertEquals(1, opens)
    }

    @Test
    fun `malformed source syntax should close once without retrying`() {
        var opens = 0
        var closes = 0

        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse({
                opens++
                check(opens == 1)
                val source = StringCharSource(")")
                object : CharSource, AutoCloseable {
                    override fun next(): String? = source.next()
                    override fun close() {
                        closes++
                    }
                }
            })
        }
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test
    fun `an observed transient cancellation should not drain a non closeable source`() {
        var opens = 0
        var reads = 0
        var observed = false
        val cancellation = ExecutionControl {
            (reads == 1 && !observed).also { if (it) observed = true }
        }

        val input = runCatching {
            SmtLib.parse(
                {
                    opens++
                    object : CharSource {
                        override fun next(): String? {
                            reads++
                            check(reads == 1)
                            return "(declare-const x Real)"
                        }
                    }
                },
                cancellation = cancellation,
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
        assertEquals(1, reads)
    }

    @Test
    fun `work exhaustion after source capture should withhold metadata without reopening`() {
        var opens = 0
        var closes = 0
        var loweringWork = 0L
        val cancellation = object : ExecutionControl() {
            override fun isCancelled(): Boolean = loweringWork > 0
            override val accounting: WorkMeter get() = WorkMeter { if (closes != 0) loweringWork += it }
        }

        val input = runCatching {
            SmtLib.parse(
                {
                    opens++
                    val source = StringCharSource(
                        "(declare-const x Real) (assert (= (exp x) 1)) (check-sat)",
                    )
                    object : CharSource, AutoCloseable {
                        override fun next(): String? = source.next()
                        override fun close() {
                            closes++
                        }
                    }
                },
                cancellation = cancellation,
            )
        }

        assertIs<CancellationException>(input.exceptionOrNull())
        assertEquals(1, opens)
        assertEquals(1, closes)
        assertTrue(loweringWork > 0)
    }
}
