package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MpsModeNumericalStatusTest(private val exact: Boolean, private val maximize: Boolean, private val mixed: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "exact={0}, maximize={1}, mixed={2}")
        fun policies(): List<Array<Boolean>> = listOf(false, true).flatMap { exact ->
            listOf(false, true).flatMap { maximize ->
                listOf(false, true).map { mixed -> arrayOf(exact, maximize, mixed) }
            }
        }
    }

    @Test
    fun `completed MPS optimization should report the configured certification scope`() {
        val source = File.createTempFile("mps-numerical-status", ".mps").apply {
            val integer = if (mixed) " M0 'MARKER' 'INTORG'\n I COST 0\n M1 'MARKER' 'INTEND'\n" else ""
            writeText("OBJSENSE\n ${if (maximize) "MAX" else "MIN"}\nROWS\n N COST\nCOLUMNS\n" +
                integer + " X COST 1\nBOUNDS\n LO B X 1\n UP B X 2\nENDATA\n")
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
        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o ${if (maximize) 2 else 1}" in text.lines(), text)
        val status = if (exact) "attained-optimum" else "tolerance-optimum"
        assertTrue("c optimizationStatus=$status" in text.lines(), text)
        assertEquals(!exact, "MPS numerical status under configured tolerances" in text, text)
        assertFalse("tolerance-accepted optimality unproved" in text, text)
    }
}
