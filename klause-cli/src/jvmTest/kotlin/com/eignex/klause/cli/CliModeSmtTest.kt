package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliModeSmtTest {
    @Test
    fun `SMT residue refutation should report consumed source authority`() {
        val source = File.createTempFile("nia-residues", ".smt2").apply {
            writeText("(declare-const x Int) (declare-const y Int) " +
                "(assert (= (+ (* x x) (* y y)) 3)) (check-sat)")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nia-residues=true", source.absolutePath)))
        }

        assertTrue("unsat" in output.lines(), output)
        assertTrue("; niaResidueRefutedScopes=1" in output.lines(), output)
        assertTrue(output.lines().any { it.matches(Regex("; niaResidueWork=[1-9][0-9]*")) }, output)
    }

    @Test
    fun `SMT integer cell reuse should report avoided completions without an unsat claim`() {
        val source = File.createTempFile("nia-cells", ".smt2").apply {
            writeText("(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2)) (check-sat)")
            deleteOnExit()
        }

        for (steering in listOf(false, true)) {
            val output = capture {
                assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nia-candidates=12",
                    "--param", "nia-cell-refinement=true", "--param", "nia-cell-reuse=true",
                    "--param", "nia-cell-steering=$steering", source.absolutePath)))
            }

            assertTrue("unknown" in output.lines(), output)
            assertTrue("; niaCellProducer=true" in output.lines(), output)
            assertTrue("; niaCellReuse=true" in output.lines(), output)
            assertTrue("; niaCellSteering=$steering" in output.lines(), output)
            assertTrue(output.lines().any { it.matches(Regex("; niaExcludedTuples=[1-9][0-9]*")) }, output)
            assertTrue(output.lines().any { it.matches(Regex("; niaRepeatedSupports=[1-9][0-9]*")) }, output)
        }
    }

    @Test
    fun `SMT integer root admission should report the refuted rootless square`() {
        val source = File.createTempFile("nia-roots", ".smt2").apply {
            writeText("(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nia-integer-roots=true", source.absolutePath)))
        }

        assertEquals("unsat", output.lineSequence().first(), output)
        assertTrue("; niaRootEquations=1" in output.lines(), output)
        assertTrue("; niaRootRefutedScopes=1" in output.lines(), output)
        assertTrue("; niaRootCompletionChecks=0" in output.lines(), output)
    }

    @Test
    fun `SMT inconsistent integer scalar equations should print unsat from the refuted route`() {
        val source = File.createTempFile("integer-premises", ".smt2").apply {
            writeText(
                """
                (set-info :smt-lib-version 2.6)
                (set-logic QF_LIA)
                (set-info :source | Leonardo de Moura |)
                (set-info :category "check")
                (set-info :status unsat)
                (set-info :notes |This benchmark is designed to check if the integer DP is complete.|)
                (declare-fun x1 () Int)
                (declare-fun x2 () Int)
                (declare-fun x3 () Int)
                (declare-fun x4 () Int)
                (assert (and (= (+ x1 x2) 8) (= x1 (* 3 x3)) (= x2 (* 6 x4))))
                (check-sat)
                (exit)
                """.trimIndent(),
            )
        }

        val output = try {
            capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-s", source.absolutePath))) }
        } finally {
            source.delete()
        }

        assertEquals("unsat", output.lineSequence().first())
        assertTrue("; frontendRoutingOutcome=refuted" in output.lines(), output)
        assertFalse("incomplete_nia" in output, output)
    }

    @Test
    fun `SMT function witness should use exact closed value constructors`() {
        val source = File.createTempFile("function-exp", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (exp x) 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-s", source.absolutePath))) }

        assertTrue("sat" in output.lines(), output)
        assertTrue("(define-fun x () Real ((_ klause-real log) 2.0))" in output, output)
        assertTrue("; solvePipeline=transcendental-theory" in output.lines(), output)
        assertTrue("; declaredTheoryRoute=transcendental" in output.lines(), output)
    }

    @Test
    fun `SMT invalid log domains should remain invalid under negation`() {
        val source = File.createTempFile("function-domain", ".smt2").apply {
            writeText("(assert (not (= (log 0) 1))) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue("unsat" in output.lines(), output)
    }

    @Test
    fun `SMT zero function work allowance should report unknown without a model`() {
        val source = File.createTempFile("function-zero", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (exp x) 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nra-work-limit=0", source.absolutePath)))
        }

        assertTrue("unknown" in output.lines(), output)
        assertFalse("define-fun" in output, output)
        assertTrue("; transcendentalWork=0" in output.lines(), output)
    }

    @Test
    fun `SMT function objectives should not acquire a satisfaction verdict`() {
        val source = File.createTempFile("function-objective", ".smt2").apply {
            writeText("(declare-const x Real) (minimize (exp x)) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue("unknown" in output.lines(), output)
        assertFalse("sat" in output.lines(), output)
        assertFalse("optimizationStatus=optimal" in output, output)
    }

    @Test
    fun `SMT quadratic optimum should report an exact incumbent and certified bound`() {
        val source = File.createTempFile("quad-optimum", ".smt2").apply {
            writeText("(declare-const x Real) (minimize (* x x)) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", "-s", source.absolutePath))) }

        assertTrue("sat" in output.lines(), output)
        assertTrue("; objective=0.0" in output.lines(), output)
        assertTrue("; objectiveBound=0.0" in output.lines(), output)
        assertTrue("; optimizationStatus=optimal" in output.lines(), output)
        assertTrue("; solvePipeline=quadratic-polynomial" in output.lines(), output)
    }

    @Test
    fun `SMT strict quadratic infimum should retain a feasible model distinct from its bound`() {
        val source = File.createTempFile("quad-infimum", ".smt2").apply {
            writeText("(declare-const x Real) (assert (> x 0)) (minimize (* x x)) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue("; optimizationStatus=unattained-infimum" in output.lines(), output)
        assertTrue("; objectiveBound=0.0" in output.lines(), output)
        assertTrue("; objectiveBoundStrict=true" in output.lines(), output)
        assertFalse("; optimizationStatus=optimal" in output.lines(), output)
        assertFalse("(define-fun x () Real 0.0)" in output, output)
    }

    @Test
    fun `SMT strict quadratic maximization should report its nonzero supremum in source direction`() {
        val source = File.createTempFile("quad-supremum", ".smt2").apply {
            writeText("(declare-const x Real) (assert (> x 0)) (maximize (- 5 (* x x))) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue("; optimizationStatus=unattained-supremum" in output.lines(), output)
        assertTrue("; objectiveBoundDirection=upper" in output.lines(), output)
        assertTrue("; objectiveBound=5.0" in output.lines(), output)
        assertFalse("; objective=5.0" in output.lines(), output)
    }

    @Test
    fun `SMT quadratic local search should preserve a wide integer objective without optimal status`() {
        val source = File.createTempFile("quad-wide", ".smt2").apply {
            writeText("(declare-const i Int) (assert (= i 100000000000000000000)) (minimize (* i i))")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "ls", "-s", "--param", "nia-moves=1", source.absolutePath)))
        }

        assertTrue("; objective=10000000000000000000000000000000000000000.0" in output.lines(), output)
        assertTrue("(define-fun i () Int 100000000000000000000)" in output, output)
        assertTrue("; optimizationStatus=best-found" in output.lines(), output)
        assertTrue("; lsMoves=1" in output.lines(), output)
    }

    @Test
    fun `SMT zero quadratic native allowance should publish unknown without a model`() {
        val source = File.createTempFile("quad-zero", ".smt2").apply {
            writeText("(declare-const x Real) (minimize (* x x))")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nra-work-limit=0", source.absolutePath)))
        }

        assertTrue("unknown" in output.lines(), output)
        assertTrue("; quadraticNativeWork=0" in output.lines(), output)
        assertFalse("define-fun" in output, output)
    }

    @Test
    fun `SMT finite decimals and integral reals should render as exact real terms`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val whole = BigFraction.ofLong(5)
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5, 5.0), listOf(half, whole))

        val model = renderModel(emptyMap(), emptyMap(), mapOf("half" to 0, "whole" to 1), sample)

        assertTrue("(define-fun half () Real (/ 1.0 2.0))" in model, model)
        assertTrue("(define-fun whole () Real 5.0)" in model, model)
    }

    @Test
    fun `SMT model should refuse an uncertified real sample`() {
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0 / 3.0))

        assertFailsWith<IllegalArgumentException> {
            renderModel(emptyMap(), emptyMap(), mapOf("x" to 0), sample)
        }
    }

    @Test
    fun `SMT polynomial witnesses should render exact irrational roots under source names`() {
        val source = File.createTempFile("nra-root", ".smt2").apply {
            writeText(
                "(declare-const |source x| Real) (assert (= (* |source x| |source x|) 2)) " +
                    "(assert (> |source x| 0)) (check-sat) (get-model)",
            )
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue("sat\n" in output, output)
        assertTrue("(define-fun |source x| () Real (root-obj (+ (- 2) (* 1 (^ x 2))) 2))" in output, output)
    }

    @Test
    fun `SMT nonlinear integer witnesses should retain wide exact values on local search`() {
        val source = File.createTempFile("nia-wide", ".smt2").apply {
            writeText(
                "(declare-const |source n| Int) (assert (= |source n| 100000000000000000000)) " +
                    "(assert (= (* |source n| |source n|) 10000000000000000000000000000000000000000))",
            )
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "ls", "-s", source.absolutePath))) }

        assertTrue(output.startsWith("sat\n"), output)
        assertTrue("(define-fun |source n| () Int 100000000000000000000)" in output, output)
        assertTrue("; lsMoves=" in output, output)
        assertTrue("; solvePipeline=nonlinear-integer-theory" in output, output)
    }

    @Test
    fun `SMT incomplete integer candidate exhaustion should publish unknown`() {
        val source = File.createTempFile("nia-budget", ".smt2").apply {
            writeText("(declare-const n Int) (assert (= (* n n) 2))")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "--param", "nia-candidates=2", source.absolutePath)))
        }

        assertEquals(listOf("unknown", "; fixed work exhausted"), output.trim().lines())
    }

    @Test
    fun `compressed polynomial sources should reopen after rejected linear admission`() {
        val source = File.createTempFile("nra-compressed", ".smt2.gz").apply {
            GZIPOutputStream(outputStream()).writer().use {
                it.write("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
            }
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertTrue(output.startsWith("sat\n"), output)
    }

    @Test
    fun `SMT polynomial strict boundaries should be provable unsatisfiable`() {
        val source = File.createTempFile("nra-strict", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture { assertEquals(0, runCli(arrayOf("-e", "cp", source.absolutePath))) }

        assertEquals("unsat", output.trim())
    }

    @Test
    fun `SMT polynomial native work exhaustion should report unknown without a witness`() {
        val source = File.createTempFile("nra-work", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-s", "--param", "nra-work-limit=0", source.absolutePath)))
        }

        assertTrue(output.startsWith("unknown\n"), output)
        assertTrue("; nraWork=0" in output, output)
        assertTrue("; solvePipeline=polynomial-theory" in output, output)
        assertTrue("; declaredTheoryRoute=exact_nra" in output, output)
        assertTrue("; reportedBackend=backtrack" in output, output)
        assertTrue("; frontendLoadMs=" in output, output)
        assertTrue("; frontendRoutingMs=" in output, output)
        assertTrue("; frontendRoutingOutcome=polynomial" in output, output)
        assertTrue("nativeCpPropagationMs=" in output, output)
        assertFalse("define-fun" in output, output)
    }

    @Test
    fun `disabling linear bound proof should not interrupt polynomial source admission`() {
        val source = File.createTempFile("nra-bound", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
            deleteOnExit()
        }

        val output = capture {
            assertEquals(0, runCli(arrayOf("--param", "open-bound-proof=false", source.absolutePath)))
        }

        assertTrue(output.startsWith("sat\n"), output)
    }

    @Test
    fun `SMT-LIB statistics should include exact theory counters`() {
        val smt = File.createTempFile("cli", ".smt2").apply {
            writeText(
                "(set-logic QF_LRA)\n(declare-const x Real)\n(assert (<= x 1))\n(check-sat)\n",
            )
            deleteOnExit()
        }

        val output = capture { runCli(arrayOf("-s", smt.absolutePath)) }

        assertTrue("; smtTheoryChecks=" in output, output)
        assertTrue("; smtWitnessCandidates=1" in output, output)
        assertEquals("sat", output.lineSequence().first())
        assertTrue("; frontendLoadMs=" in output, output)
        assertTrue("; frontendRoutingMs=" in output, output)
        assertTrue("; frontendRoutingOutcome=open" in output, output)
        assertTrue("; solvePipeline=open-theory" in output, output)
        assertTrue("; declaredTheoryRoute=exact_lra" in output, output)
        assertFalse("; nativeCpRootPropagationMs=" in output, output)
        assertFalse("optimizationStatus=" in output, output)
    }

    @Test
    fun `SMT finite pipeline statistics should remain optional comments after the verdict`() {
        val smt = File.createTempFile("cliphases", ".smt2").apply {
            writeText("(declare-const b Bool)\n(assert b)\n(check-sat)\n")
            deleteOnExit()
        }

        val measured = capture { runCli(arrayOf("-s", "-e", "backtrack", smt.absolutePath)) }
        val ordinary = capture { runCli(arrayOf("-e", "backtrack", smt.absolutePath)) }

        assertEquals("sat", measured.lineSequence().first())
        assertEquals("sat", ordinary.lineSequence().first())
        assertTrue("; solvePipeline=finite-cp" in measured, measured)
        assertTrue("; frontendRoutingOutcome=finite" in measured, measured)
        assertFalse("declaredTheoryRoute=" in measured, measured)
        assertFalse("solvePipeline=" in ordinary, ordinary)
        assertFalse("frontendLoadMs=" in ordinary, ordinary)
    }

    @Test
    fun `SMT optimization objectives should use the source direction`() {
        for ((sort, bound) in listOf("Int" to "2", "Real" to "1.5")) {
            for (direction in listOf("minimize", "maximize")) {
                val bounds = if (sort == "Int") {
                    "(assert (>= x (- $bound))) (assert (<= x $bound))"
                } else {
                    """
                    (declare-const y Int)
                    (assert (= (* 2 x) y))
                    (assert (>= y (- 3))) (assert (<= y 3))
                    """.trimIndent()
                }
                val smt = File.createTempFile("clidirection", ".smt2").apply {
                    writeText(
                        """
                        (declare-const x $sort)
                        $bounds
                        ($direction x)
                        (check-sat)
                        """.trimIndent(),
                    )
                    deleteOnExit()
                }

                val out = capture { assertEquals(0, runCli(arrayOf("-e", "cp", smt.absolutePath))) }

                val magnitude = if (sort == "Real") "3/2" else bound
                val expected = if (direction == "minimize") "-$magnitude" else magnitude
                assertTrue("sat" in out.lines(), "$sort $direction: $out")
                assertTrue(out.lines().any { it.startsWith("; objective=") }, "$sort $direction: $out")
                assertEquals("; objective=$expected", out.lines().last { it.startsWith("; objective=") }, out)
                assertTrue("; optimizationStatus=optimal" in out.lines(), out)
            }
        }
    }

    @Test
    fun `SMT objectives should preserve precision in either direction`() {
        val (lower, upper) = "9223372036854775808" to "9223372036854775809"
        for (direction in listOf("minimize", "maximize")) {
            val smt = File.createTempFile("cliprecision", ".smt2").apply {
                writeText(
                    """
                    (declare-const x Int)
                    (assert (>= x $lower)) (assert (<= x $upper))
                    ($direction x)
                    (check-sat)
                    """.trimIndent(),
                )
                deleteOnExit()
            }

            val out = capture { assertEquals(0, runCli(arrayOf("-e", "cp", smt.absolutePath))) }
            val expected = if (direction == "minimize") lower else upper

            assertEquals("; objective=$expected", out.lines().last { it.startsWith("; objective=") }, out)
            assertTrue("; optimizationStatus=optimal" in out.lines(), out)
        }
    }

    @Test
    fun `SMT optimization should report an unbounded descent beside its witness`() {
        val smt = File.createTempFile("cliunbounded", ".smt2").apply {
            writeText(
                "(declare-const x Int)\n(assert (<= x 9))\n(minimize x)\n(check-sat)\n",
            )
            deleteOnExit()
        }

        val out = capture { assertEquals(0, runCli(arrayOf(smt.absolutePath))) }

        assertTrue("sat" in out.lines(), out)
        assertTrue("; optimizationStatus=unbounded" in out.lines(), out)
        assertTrue("(define-fun x () Int" in out, out)
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
}
