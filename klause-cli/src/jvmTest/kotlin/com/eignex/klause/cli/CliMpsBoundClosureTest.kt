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
class CliMpsBoundClosureTest(private val exact: Boolean, private val boundProof: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "exact={0}, bound-proof={1}")
        fun policies(): List<Array<Any>> = listOf(false, true).flatMap { exact ->
            listOf(true, false).map { proof -> arrayOf<Any>(exact, proof) }
        }
    }

    @Test
    fun `MPS bound closure should retain the optimum under its certification policy`() {
        val mps = File.createTempFile("cli", ".mps").apply {
            writeText(
                "NAME          T\nROWS\n N  COST\n L  R1\nCOLUMNS\n" +
                    "    MARKER                 'MARKER'                 'INTORG'\n" +
                    "    x  COST  -1.0   R1  1.0\n" +
                    "    MARKER                 'MARKER'                 'INTEND'\n" +
                    "RHS\n    RHS  R1  5.0\nBOUNDS\n LI BND  x  0\nENDATA\n",
            )
            deleteOnExit()
        }
        val flags = arrayOf("-s", "-e", "cp", "-p", "1", "-t", "10000",
            "--param", "presolve-work-limit=4096", "--param", "source-lp-work-limit=65536") +
            (if (exact) arrayOf("--exact") else emptyArray()) +
            (if (boundProof) emptyArray() else arrayOf("--param", "open-bound-proof=false"))
        val oldBudget = System.getProperty(CliKnobs.presolveBudgetMs)
        val out = try {
            System.setProperty(CliKnobs.presolveBudgetMs, "1")
            capture { assertEquals(0, runCli(flags + mps.absolutePath)) }
        } finally {
            if (oldBudget == null) {
                System.clearProperty(CliKnobs.presolveBudgetMs)
            } else {
                System.setProperty(CliKnobs.presolveBudgetMs, oldBudget)
            }
        }

        assertTrue("s OPTIMUM FOUND" in out.lines(), out)
        assertTrue("o -5" in out.lines(), out)
        assertTrue("v x=5" in out.lines(), out)
        if (!boundProof) {
            assertTrue("c frontendRoutingOutcome=native-formula" in out.lines(), out)
            assertTrue("c reportedBackend=numerical-mip" in out.lines(), out)
            assertFalse("cancelled" in out, out)
        }
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
