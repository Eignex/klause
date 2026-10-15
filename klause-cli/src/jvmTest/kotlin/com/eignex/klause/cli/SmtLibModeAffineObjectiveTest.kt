package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtLibModeAffineObjectiveTest(private val engine: String, private val zeroSquare: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} zeroSquare={1}")
        fun variants(): List<Array<Any>> = listOf("cp", "ls").flatMap { engine ->
            listOf(false, true).map { arrayOf<Any>(engine, it) }
        }
    }

    @Test
    fun `source affine and zero quadratic encodings should publish the same exact polynomial result`() {
        val term = if (zeroSquare) "(+ y (* 0 x x))" else "y"
        val source = File.createTempFile("affine-polynomial", ".smt2").apply {
            writeText(
                """
                (declare-const i Int) (declare-const x Real) (declare-const y Real)
                (assert (and (>= i 0) (<= i 1))) (assert (= x 1))
                (assert (= (* (to_real i) x) y))
                (minimize (+ 7 $term)) (check-sat)
                """.trimIndent(),
            )
        }
        val bytes = ByteArrayOutputStream()
        val original = System.out
        val output = PrintStream(bytes)
        var code = -1

        try {
            System.setOut(output)
            code = runCli(
                arrayOf(
                    "-s", "-e", engine, "-p", "1", "-r", "17",
                    "--param", "max-flips=1", "--param", "alns-iterations=1", source.absolutePath,
                ),
            )
        } finally {
            System.setOut(original)
            output.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        val integer = assertNotNull(
            Regex("""\(define-fun (?:\|i\||i) \(\) Int ([01])\)""").findAll(text).lastOrNull(),
            text,
        ).groupValues[1].toInt()
        val status = if (engine == "ls") "best-found" else "optimal"
        assertEquals(0, code, text)
        assertTrue("; objective=${7 + integer}.0" in text.lines(), text)
        assertTrue("; optimizationStatus=$status" in text.lines(), text)
        assertTrue(text.lines().any { it.startsWith("; quadraticNativeWork=") && !it.endsWith("=0") }, text)
        assertTrue("(define-fun |y| () Real $integer.0)" in text || "(define-fun y () Real $integer.0)" in text, text)
    }
}
