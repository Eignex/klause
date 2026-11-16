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
) {
    companion object {
        private const val DECIMAL = " G R\nCOLUMNS\n X COST -1 R 0.1\n Y COST 0 R 1\nRHS\n RHS R 0.3\n"
        private const val DYADIC = " G R\nCOLUMNS\n X COST -1 R 0.5\n Y COST 0 R 1\nRHS\n RHS R 0.5\n"
        private const val LEVEL = " E R\nCOLUMNS\n X R 0.1\n Y R 0.2\n Z COST -1 R -0.3\n"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}, exact={2}")
        fun models(): List<Array<Any>> = listOf(false, true).flatMap { exact ->
            listOf(
                arrayOf<Any>("decimal ray", DECIMAL, exact),
                arrayOf<Any>("dyadic ray", DYADIC, exact),
                arrayOf<Any>("binary64 level ray", LEVEL, exact),
            )
        }
    }

    @Test
    fun `an MPS source with an exact improving ray should report unboundedness`() {
        val source = File.createTempFile("mps-unbounded-status", ".mps").apply {
            writeText("ROWS\n N COST\n${coefficients}ENDATA\n")
        }
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(bytes)
        val flags = if (exact) arrayOf("--exact") else emptyArray()

        try {
            System.setOut(stream)
            val arguments = arrayOf("-e", "cp", "-p", "1", "--param", "node-limit=1000", *flags, source.absolutePath)
            assertEquals(0, runCli(arguments))
        } finally {
            System.setOut(previous)
            stream.close()
            source.delete()
        }

        val text = bytes.toString(Charsets.UTF_8.name())
        assertTrue("s UNBOUNDED" in text.lines(), text)
    }
}
