package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class MpsModeUnboundedStatusTest(
    private val name: String,
    private val coefficients: String,
    private val exact: Boolean,
    private val status: String,
) {
    companion object {
        private const val UNBOUNDED = " G R\nCOLUMNS\n X COST -1 R 0.1\n Y COST 0 R 1\nRHS\n RHS R 0.3\n"
        private const val DYADIC = " G R\nCOLUMNS\n X COST -1 R 0.5\n Y COST 0 R 1\nRHS\n RHS R 0.5\n"
        private const val LEVEL = " E R\nCOLUMNS\n X R 0.1\n Y R 0.2\n Z COST -1 R -0.3\n"
        private const val BOUNDED = " L R\nCOLUMNS\n X COST -1 R 0.1\n Y COST 0 R 1\nRHS\n RHS R 0.3\n"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}, exact={2}")
        fun models(): List<Array<Any>> = listOf(false, true).flatMap { exact ->
            listOf(
                arrayOf<Any>("decimal ray", UNBOUNDED, exact, "s UNBOUNDED"),
                arrayOf<Any>("dyadic ray", DYADIC, exact, "s UNBOUNDED"),
                arrayOf<Any>("binary64 level ray", LEVEL, exact, "s UNBOUNDED"),
                arrayOf<Any>("finite decimal optimum", BOUNDED, exact, "s OPTIMUM FOUND"),
            )
        }
    }

    @Test
    fun `completed MPS optimization should report unboundedness only for a source ray`() {
        val source = File.createTempFile("mps-unbounded-status", ".mps").apply {
            writeText("ROWS\n N COST\n${coefficients}ENDATA\n")
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)
        val flags = if (exact) arrayOf("--exact") else emptyArray()

        try {
            System.setOut(stream)
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue(status in text.lines(), text)
    }
}
