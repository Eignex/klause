package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerdictReasonTest {

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

    private fun smt(context: VerdictContext, verdict: Verdict): String = capture {
        SmtLibOutput().apply {
            onVerdictContext(context)
            onComplete(verdict)
        }
    }

    @Test
    fun `an exhausted budget is named as the cause`() {
        val out = smt(VerdictContext(budgetExhausted = true), Verdict.UNKNOWN)
        assertTrue("; budget exhausted" in out, out)
    }

    @Test
    fun `a pool with nothing that can prove is named as the cause`() {
        val out = smt(VerdictContext(budgetExhausted = true, completePool = false), Verdict.UNKNOWN)
        assertTrue("no arm in the pool can prove" in out, out)
    }

    @Test
    fun `a decided verdict carries no reason line`() {
        val out = smt(VerdictContext(), Verdict.SATISFIABLE)
        assertTrue("unknown" !in out, out)
    }

    @Test
    fun `every competition mode names the cause of an unknown`() {
        // The census of unknowns by cause reads these lines, so a mode without one is a corpus the
        // census cannot see rather than a corpus with nothing to report.
        val modes = listOf<Pair<String, () -> BufferedBestOutput>>(
            "dimacs" to { DimacsOutput() },
            "opb" to { OpbOutput() },
            "wcnf" to { WcnfOutput() },
            "xcsp" to { XcspOutput() },
            "mps" to { MpsOutput() },
            "smtlib" to { SmtLibOutput() },
        )
        for ((name, factory) in modes) {
            val out = capture {
                factory().apply {
                    onVerdictContext(VerdictContext(budgetExhausted = true))
                    onComplete(Verdict.UNKNOWN)
                }
            }
            assertTrue("budget exhausted" in out, "$name reported no cause: $out")
        }
    }

    @Test
    fun `the flatzinc mode names the cause as a comment beside its terminator`() {
        val out = capture {
            MiniZincOutput().apply {
                onVerdictContext(VerdictContext(budgetExhausted = true))
                onComplete(Verdict.UNKNOWN)
            }
        }
        assertEquals("=====UNKNOWN=====", out.lineSequence().first())
        assertTrue("% budget exhausted" in out, out)
    }

    @Test
    fun `the cause does not repeat the verdict the status line already gave`() {
        val out = smt(VerdictContext(budgetExhausted = true), Verdict.UNKNOWN)
        assertEquals("unknown", out.lineSequence().first())
        assertEquals(1, out.lineSequence().count { "unknown" in it }, "the verdict is named once: $out")
    }

    @Test
    fun `the flatzinc mode leaves a decided verdict alone`() {
        val out = capture {
            MiniZincOutput().apply {
                onVerdictContext(VerdictContext(budgetExhausted = true))
                onComplete(Verdict.SATISFIABLE)
            }
        }
        assertEquals("==========", out.trim())
    }
}
