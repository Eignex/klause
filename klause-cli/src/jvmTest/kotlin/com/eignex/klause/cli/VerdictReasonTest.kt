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

    private fun smt(clamped: Boolean, boxFree: Boolean, context: VerdictContext, verdict: Verdict): String {
        val clamp = ClampFlag().apply {
            this.clamped = clamped
            boxFreeRefutation = { boxFree }
        }
        return capture {
            SmtLibOutput(clamp).apply {
                onVerdictContext(context)
                onComplete(verdict)
            }
        }
    }

    @Test
    fun `a refutation the clamp blocked says so rather than reading as a plain unknown`() {
        val out = smt(clamped = true, boxFree = false, VerdictContext(), Verdict.UNSATISFIABLE)
        assertEquals("unknown", out.lineSequence().first())
        assertTrue("clamped search range" in out, out)
    }

    @Test
    fun `an exhausted budget is named as the cause`() {
        val out = smt(false, false, VerdictContext(budgetExhausted = true), Verdict.UNKNOWN)
        assertTrue("; unknown: budget exhausted" in out, out)
    }

    @Test
    fun `a pool with nothing that can prove is named as the cause`() {
        val out = smt(false, false, VerdictContext(budgetExhausted = true, completePool = false), Verdict.UNKNOWN)
        assertTrue("no arm in the pool can prove" in out, out)
    }

    @Test
    fun `a refutation that survives without the box reports unsat and no reason`() {
        val out = smt(clamped = true, boxFree = true, VerdictContext(), Verdict.UNSATISFIABLE)
        assertEquals("unsat", out.trim())
    }

    @Test
    fun `a decided verdict carries no reason line`() {
        val out = smt(false, false, VerdictContext(), Verdict.SATISFIABLE)
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
            "mps" to { MpsOutput(ClampFlag(), globalOptimum = { true }) },
            "smtlib" to { SmtLibOutput() },
        )
        for ((name, factory) in modes) {
            val out = capture {
                factory().apply {
                    onVerdictContext(VerdictContext(budgetExhausted = true))
                    onComplete(Verdict.UNKNOWN)
                }
            }
            assertTrue("unknown: budget exhausted" in out, "$name reported no cause: $out")
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
        assertTrue("% unknown: budget exhausted" in out, out)
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

    @Test
    fun `an optimum only optimal inside the clamp says so`() {
        val out = capture {
            MpsOutput(ClampFlag().apply { clamped = true }, globalOptimum = { false }).apply {
                onVerdictContext(VerdictContext())
                onSolution("v x=1", 7L)
                onComplete(Verdict.OPTIMAL)
            }
        }
        assertTrue("c satisfiable: optimal within the clamped search range only" in out, out)
    }
}
