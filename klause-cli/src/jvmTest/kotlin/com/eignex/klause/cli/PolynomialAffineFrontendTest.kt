package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PolynomialAffineFrontendTest(
    private val engine: String,
    private val direction: String,
    private val zeroSquare: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} zeroSquare={2}")
        fun variants(): List<Array<Any>> = listOf("cp", "fixed", "ls", "alns", "mixed").flatMap { engine ->
            listOf("minimize", "maximize").flatMap { direction ->
                listOf(false, true).map { arrayOf<Any>(engine, direction, it) }
            }
        }
    }

    @Test
    fun `source affine and zero quadratic encodings publish the same exact polynomial result`() {
        val term = if (zeroSquare) "(+ y (* 0 x x))" else "y"
        val source = File.createTempFile("affine-polynomial", ".smt2").apply {
            writeText("""
                (declare-const i Int) (declare-const x Real) (declare-const y Real)
                (assert (and (>= i 0) (<= i 1))) (assert (= x 1))
                (assert (= (* (to_real i) x) y))
                ($direction (+ 7 $term)) (check-sat)
            """.trimIndent())
        }
        val bytes = ByteArrayOutputStream()
        val original = System.out
        val output = PrintStream(bytes)
        var code = -1

        try {
            System.setOut(output)
            code = runCli(arrayOf("-s", "-e", engine, "-p", "1", "-r", "17",
                "--param", "max-flips=1", "--param", "alns-iterations=1", source.absolutePath))
        } finally {
            System.setOut(original)
            output.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        val expected = if (direction == "minimize") 7 else 8
        val integer = assertNotNull(Regex("""\(define-fun (?:\|i\||i) \(\) Int ([01])\)""").findAll(text).lastOrNull(), text)
            .groupValues[1].toInt()
        assertEquals(0, code, text)
        assertTrue("sat" in text.lines(), text)
        assertTrue("; objective=${7 + integer}.0" in text.lines(), text)
        if (engine == "ls" || engine == "alns") {
            assertTrue("; optimizationStatus=best-found" in text.lines(), text)
        } else {
            assertEquals(expected - 7, integer, text)
            assertTrue("; optimizationStatus=optimal" in text.lines(), text)
            assertTrue("; objectiveBound=$expected.0" in text.lines(), text)
        }
        val nativeWork = text.lines().single { it.startsWith("; quadraticNativeWork=") }
            .substringAfter('=').toLong()
        assertTrue(nativeWork > 0, text)
        if (engine == "ls" || engine == "alns" || engine == "mixed") {
            assertTrue("; lsMoves=1" in text.lines(), text)
        }
        assertTrue("(define-fun |i| () Int $integer)" in text ||
            "(define-fun i () Int $integer)" in text, text)
        assertTrue("(define-fun |x| () Real 1.0)" in text ||
            "(define-fun x () Real 1.0)" in text, text)
        assertTrue("(define-fun |y| () Real $integer.0)" in text ||
            "(define-fun y () Real $integer.0)" in text, text)
    }
}
