package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A weighted triangle cover, maximized with negated costs: the fixed engine's first incumbent costs 2.1 and the
// optimum 2.
@RunWith(Parameterized::class)
class MpsOptimalityGapTest(private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "maximize={0}")
        fun directions(): List<Boolean> = listOf(false, true)
    }

    private val sign = if (maximize) "-" else ""

    @Test
    fun `an incumbent within the optimality gap should be reported optimal with its proved bound`() {
        val text = triangleCover("--source-relative-optimality-tolerance", "1/2")

        assertTrue("s OPTIMUM FOUND" in text.lines(), text)
        assertTrue("o ${sign}2.1" in text.lines(), text)
        assertTrue("c optimizationStatus=gap-optimum" in text.lines(), text)
        assertTrue("c objectiveBoundDirection=${if (maximize) "upper" else "lower"}" in text.lines(), text)
        assertTrue("c objectiveBound=${sign}2" in text.lines(), text)
    }

    @Test
    fun `exact MPS loading should close the bound before claiming an optimum`() {
        val text = triangleCover("--exact")

        assertTrue("o ${sign}2" in text.lines(), text)
        assertTrue("c optimizationStatus=attained-optimum" in text.lines(), text)
        assertFalse("objectiveBound=" in text, text)
    }

    private fun triangleCover(vararg flags: String): String {
        val source = File.createTempFile("mps-triangle-cover", ".mps").apply {
            writeText(
                (if (maximize) "OBJSENSE\n MAX\n" else "") +
                    "ROWS\n N COST\n G R1\n G R2\n G R3\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                    " X1 COST ${sign}1.1 R1 1\n X1 R3 1\n X2 COST ${sign}1 R1 1\n X2 R2 1\n" +
                    " X3 COST ${sign}1 R2 1\n X3 R3 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS R1 1 R2 1\n RHS R3 1\n" +
                    "BOUNDS\n UP B X1 1\n UP B X2 1\n UP B X3 1\nENDATA\n",
            )
            deleteOnExit()
        }
        return captureStdout {
            assertEquals(0, runCli(arrayOf("-e", "cp", "-p", "1", *flags, source.absolutePath)))
        }
    }
}
