package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliModeOpenTheoryTest {

    @Test
    fun `an open theory wall timeout should name the cause`() {
        val smt = File.createTempFile("clibudget", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (assert (>= x 5))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        // A zero wall budget is already spent at the first poll, so the cause is decided rather than raced.
        val out = capture { code = runCli(arrayOf("-s", "-e", "backtrack", "-t", "0", smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "unknown", out)
        assertTrue("; wall timeout" in out, out)
    }

    @Test
    fun `an open theory optimum should report its incumbent under all-solutions`() {
        val mps = File.createTempFile("cliopenall", ".mps").apply {
            writeText(
                "NAME          T\nROWS\n N  COST\n G  R1\nCOLUMNS\n" +
                    "    MARKER                 'MARKER'                 'INTORG'\n" +
                    "    x  COST  1.0   R1  1.0\n" +
                    "    MARKER                 'MARKER'                 'INTEND'\n" +
                    "RHS\n    RHS  R1  2.0\nBOUNDS\n LI BND  x  0\nENDATA\n",
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf("-a", "-s", "-t", "10000", mps.absolutePath)) }

        assertEquals(0, code, out)
        assertFalse("openTheoryChecks=0" in out, out)
        assertTrue("v x=2" in out, out)
    }

    @Test
    fun `an open theory satisfaction model should refuse all-solutions`() {
        val smt = File.createTempFile("cliopenenum", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (assert (>= x 5))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val err = captureErr { code = runCli(arrayOf("-a", smt.absolutePath)) }

        assertEquals(2, code, err)
        assertTrue("all-solution enumeration is unavailable" in err, err)
    }

    @Test
    fun `an open theory fixed work limit should name the cause and print counters`() {
        val smt = File.createTempFile("cliopenwork", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (declare-const y Int)
                (declare-const b Bool)
                (assert (<= (+ (* 2 x) y) 3))
                (assert (or b (not b)))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture {
            code = runCli(arrayOf("-s", "-e", "backtrack", "--param", "node-limit=0", smt.absolutePath))
        }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "unknown", out)
        assertTrue("; fixed work exhausted" in out, out)
        assertTrue("; openWork=0" in out, out)
    }

    @Test
    fun `open branching policies should reach the same verdict and an unknown one should be refused`() {
        val smt = File.createTempFile("clibranching", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (declare-const p Bool)
                (declare-const q Bool)
                (assert (>= x 5))
                (assert (or p q))
                (assert (or (not p) (not q)))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val activity = capture {
            assertEquals(0, runCli(arrayOf("--param", "open-branching=activity", smt.absolutePath)))
        }
        val sourceOrder = capture {
            assertEquals(0, runCli(arrayOf("--param", "open-branching=source-order", smt.absolutePath)))
        }
        val automatic = capture {
            assertEquals(0, runCli(arrayOf("--param", "open-branching=auto", smt.absolutePath)))
        }
        var code = -1
        val err = captureErr {
            code = runCli(arrayOf("--param", "open-branching=nope", smt.absolutePath))
        }

        assertEquals("sat", activity.lines().firstOrNull(), activity)
        assertEquals("sat", sourceOrder.lines().firstOrNull(), sourceOrder)
        assertEquals("sat", automatic.lines().firstOrNull(), automatic)
        assertEquals(2, code, err)
        assertTrue("open-branching" in err, err)
    }

    @Test
    fun `an open theory hint allowance should be off unless asked for and report what it drew`() {
        val smt = File.createTempFile("clihint", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (declare-const p Bool)
                (declare-const q Bool)
                (assert (>= x 5))
                (assert (or p q))
                (assert (or (not p) (not q)))
                (assert (=> p (>= x 6)))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val plain = capture { assertEquals(0, runCli(arrayOf("-s", smt.absolutePath))) }
        val deferred = capture {
            assertEquals(0, runCli(arrayOf("-s", "--param", "open-hint-flips=1000", smt.absolutePath)))
        }
        val hinted = capture {
            val args = arrayOf(
                "-s",
                "--param",
                "open-hint-flips=1000",
                "--param",
                "open-hint-min-splits=1",
                smt.absolutePath,
            )
            assertEquals(0, runCli(args))
        }

        assertTrue("; openHintDraws=" !in plain, plain)
        assertTrue("; openHintNoProvider=1" in plain, plain)
        assertTrue("; openHintDraws=" !in deferred, deferred)
        assertTrue(hinted.lines().firstOrNull() == "sat", hinted)
        assertTrue("; openHintDraws=1" in hinted, hinted)
        assertTrue("; openHintProduced=1" in hinted, hinted)
    }

    @Test
    fun `an open theory shared decision limit should name the cause and print counters`() {
        val smt = File.createTempFile("clidecisions", ".smt2").apply {
            writeText(
                """
                (declare-const x Int)
                (declare-const b Bool)
                (assert (>= x 5))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture {
            code = runCli(arrayOf("-s", "-e", "backtrack", "--param", "max-decisions=0", smt.absolutePath))
        }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "unknown", out)
        assertTrue("; fixed work exhausted" in out, out)
        assertTrue("; openBoolDecisions=0" in out, out)
    }

    @Test
    fun `an open SMT objective over a continuous column should be searched by local search`() {
        val smt = File.createTempFile("cliopenrealobjective", ".smt2").apply {
            writeText(
                """
                (set-logic QF_LIRA)
                (declare-const x Int)
                (declare-const y Real)
                (assert (>= x 3))
                (assert (>= y (to_real x)))
                (minimize y)
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }
        var code = -1
        val out = capture { code = runCli(arrayOf("-e", "ls", "--param", "node-limit=100", smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue("sat" in out.lines(), out)
    }

    // 2x + y <= 3 over open columns, so every engine with an open route finds a witness.

    @Test
    fun `constant SMT sources should execute with ALNS`() {
        for ((source, expected) in listOf(
            "" to "sat",
            "(assert (= 0.0 0.0))" to "sat",
            "(assert (= 0.0 1.0))" to "unsat",
        )) {
            val smt = File.createTempFile("cliconstant", ".smt2").apply {
                writeText(source)
                deleteOnExit()
            }
            var code = -1

            val out = capture { code = runCli(arrayOf("-e", "alns", smt.absolutePath)) }

            assertEquals(0, code, out)
            assertEquals(expected, out.lines().first(), out)
        }
    }

    @Test
    fun `alns should search an open model with exact source completion`() {
        var code = -1
        val out = capture { code = runCli(arrayOf("-e", "alns", "--param", "max-flips=30", openSmt().absolutePath)) }

        assertEquals(0, code, out)
        assertEquals("sat", out.lines().first(), out)
        assertTrue("(define-fun x () Int" in out, out)
        assertTrue("(define-fun y () Int" in out, out)
    }

    @Test
    fun `an open SMT LRA model should render an exact rational witness`() {
        val smt = File.createTempFile("clilra", ".smt2").apply {
            writeText(
                """
                (set-logic QF_LRA)
                (declare-const x Real)
                (assert (= x (/ 1.0 3.0)))
                (check-sat)
                """.trimIndent(),
            )
            deleteOnExit()
        }

        var code = -1
        val out = capture { code = runCli(arrayOf(smt.absolutePath)) }

        assertEquals(0, code, out)
        assertTrue(out.lines().firstOrNull() == "sat", out)
        assertTrue("(define-fun x () Real (/ 1.0 3.0))" in out, out)
    }

    @Test
    fun `bounded SMT mixed model should preserve a nonterminating rational witness`() {
        val smt = File.createTempFile("clirational", ".smt2").apply {
            writeText(
                """(set-logic QF_LIRA)
(set-option :produce-models true)
(declare-const x Real) (declare-const y Int) (assert (= (* 3 x) y)) (assert (> y 1)) (assert (< y 4)) (assert (distinct x 1))
(check-sat)
(get-model)
""",
            )
            deleteOnExit()
        }

        val out = capture { assertEquals(0, runCli(arrayOf("-p", "1", "-r", "570", "-t", "5000", smt.absolutePath))) }

        assertEquals("sat", out.lines().firstOrNull(), out)
        assertTrue("(define-fun y () Int 2)" in out, out)
        assertTrue("(define-fun x () Real (/ 2.0 3.0))" in out, out)
        assertFalse("0.6666666666666666" in out, out)
    }

    private fun openSmt(): File = File.createTempFile("cliopenengine", ".smt2").apply {
        writeText(
            """
            (declare-const x Int)
            (declare-const y Int)
            (assert (<= (+ (* 2 x) y) 3))
            (check-sat)
            """.trimIndent(),
        )
        deleteOnExit()
    }

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }

    private fun captureErr(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(buf))
        try {
            block()
        } finally {
            System.setErr(old)
        }
        return buf.toString()
    }
}
