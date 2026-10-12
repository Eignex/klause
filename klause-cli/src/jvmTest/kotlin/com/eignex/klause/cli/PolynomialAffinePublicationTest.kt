package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PolynomialAffinePublicationTest(private val direction: String, private val zeroSquare: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} zeroSquare={1}")
        fun variants(): List<Array<Any>> = listOf("minimize", "maximize").flatMap { direction ->
            listOf(false, true).map { arrayOf<Any>(direction, it) }
        }
    }

    @Test
    fun `polynomial affine source publication retains exact cost and bound direction`() {
        val term = if (zeroSquare) "(+ i (* 0 x x))" else "i"
        val source = File.createTempFile("affine-polynomial-publication", ".smt2").apply {
            writeText("""
                (declare-const i Int) (declare-const x Real)
                (assert (and (>= i 0) (<= i 1))) (assert (= x 1)) (assert (= (* x x) x))
                ($direction (+ 7 $term)) (check-sat)
            """.trimIndent())
        }
        val bytes = ByteArrayOutputStream()
        val original = System.out
        val output = PrintStream(bytes)
        var code = -1

        try {
            System.setOut(output)
            code = runCli(arrayOf("-e", "cp", "-p", "1", "-r", "17", source.absolutePath))
        } finally {
            System.setOut(original)
            output.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        val expected = if (direction == "minimize") 7 else 8
        val boundDirection = if (direction == "minimize") "lower" else "upper"
        assertEquals(0, code, text)
        assertTrue("sat" in text.lines(), text)
        assertTrue("; optimizationStatus=optimal" in text.lines(), text)
        assertTrue("; objective=$expected.0" in text.lines(), text)
        assertTrue("; objectiveBound=$expected.0" in text.lines(), text)
        assertTrue("; objectiveBoundDirection=$boundDirection" in text.lines(), text)
        assertTrue("(define-fun |i| () Int ${expected - 7})" in text, text)
        assertTrue("(define-fun |x| () Real 1.0)" in text, text)
    }
}
