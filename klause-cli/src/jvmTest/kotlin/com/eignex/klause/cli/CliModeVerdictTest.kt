package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.flatZincSemantics
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.smtLibSemantics
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.result.ConflictAnalysisStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.kumulant.stat.summary.SumResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliModeVerdictTest {
    @Test
    fun `source-qualified output should retain rational policy and the selected protocol terminal`() {
        val semantics = smtLibSemantics.resolve(SemanticOverrides(undeclaredReals = BoundsOverride(
            upper = BoundOverride.Value(SemanticRational.of(bigIntOf(1), bigIntOf(3))),
        )))

        val out = capture {
            SmtLibOutput().sourceQualified(semantics, ";").apply {
                begin(optimize = false, maximize = false)
                onComplete(Verdict.UNSATISFIABLE)
            }
        }

        assertTrue("; klause-source-semantics: format=SMTLIB" in out, out)
        assertTrue("unbounded-real-lo=open unbounded-real-hi=1/3" in out, out)
        assertTrue(out.endsWith("unsat\n"), out)
    }

    @Test
    fun `caller integer bounds should qualify output and withhold default source terminal claims`() {
        val semantics = flatZincSemantics.resolve(
            SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Value(-20))),
        )
        for (verdict in listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED, Verdict.OPTIMAL)) {
            val out = capture {
                MiniZincOutput(sourceSemantics = semantics).apply {
                    begin(optimize = verdict != Verdict.UNSATISFIABLE, maximize = false)
                    onComplete(verdict)
                }
            }

            assertTrue("unbounded-int-lo=-20 unbounded-int-hi=1000000" in out, out)
            assertTrue("no default-source proof" in out, out)
            assertTrue("=====UNSATISFIABLE=====" !in out && "=====UNBOUNDED=====" !in out, out)
            assertTrue("==========" !in out, out)
        }
    }

    @Test
    fun `caller real bounds should qualify output and withhold default source terminal claims`() {
        for ((lower, text) in listOf(
            SemanticRational.ofLong(-20) to "-20",
            SemanticRational.of(bigIntOf(-1), bigIntOf(2)) to "-1/2",
        )) {
            val semantics = flatZincSemantics.resolve(SemanticOverrides(
                undeclaredReals = BoundsOverride(lower = BoundOverride.Value(lower)),
            ))
            for (verdict in listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED, Verdict.OPTIMAL)) {
                val out = capture {
                    MiniZincOutput(sourceSemantics = semantics).apply {
                        begin(optimize = verdict != Verdict.UNSATISFIABLE, maximize = false)
                        onComplete(verdict)
                    }
                }

                assertTrue("unbounded-real-lo=$text unbounded-real-hi=open" in out, out)
                assertTrue("no default-source proof" in out, out)
                assertTrue("=====UNSATISFIABLE=====" !in out && "=====UNBOUNDED=====" !in out, out)
                assertTrue("==========" !in out, out)
            }
        }
    }

    @Test
    fun `unattained bound metadata should retain the caller range qualification`() {
        for (bounded in listOf(false, true)) {
            val bounds = if (bounded) BoundsOverride(upper = BoundOverride.Value(SemanticRational.ONE)) else null
            val program = parseFlatZinc(
                "var float: x; constraint float_lt(0.0, x); solve minimize x;",
                semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous, undeclaredReals = bounds),
            )
            val result = BacktrackSolver.source(program.problem).optimizeOpen(
                LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            )
            val linear = assertNotNull(assertIs<OpenTheoryExecution.Optimize>(result).result.linearResult)

            val out = capture {
                MiniZincOutput(sourceSemantics = program.semantics).apply {
                    begin(optimize = true, maximize = false)
                    onLinearResult(linear)
                }
            }

            assertEquals(!bounded, "optimizationStatus=unattained-infimum" in out, out)
            assertEquals(!bounded, "objectiveBound=" in out, out)
        }
    }

    @Test
    fun `grid completion should not publish source refutation or unboundedness`() {
        for (verdict in listOf(Verdict.UNSATISFIABLE, Verdict.UNBOUNDED)) {
            val out = capture { MiniZincOutput(floatApproximation = true).onComplete(verdict) }

            assertTrue("=====UNKNOWN=====" in out, out)
            assertTrue("no source proof" in out, out)
            assertTrue("=====UNSATISFIABLE=====" !in out && "=====UNBOUNDED=====" !in out, out)
        }
    }

    @Test
    fun `grid incumbent should not publish a source optimum marker`() {
        val out = capture {
            MiniZincOutput(floatApproximation = true).apply {
                onSolution("x = 0.5;\n----------\n", 1L, null)
                onComplete(Verdict.OPTIMAL)
            }
        }

        assertTrue("x = 0.5;\n----------" in out, out)
        assertTrue("==========" !in out, out)
        assertTrue("source witness check required" in out, out)
    }

    @Test
    fun `SMT output should preserve arbitrary integers and rational objectives`() {
        for ((numerator, denominator) in listOf(
            "9223372036854775808" to 1,
            "9007199254740993" to 1,
            "-1" to 3,
        )) {
            val objective = BigFraction.of(parseBigInt(numerator), bigIntOf(denominator))
            val out = capture {
                SmtLibOutput().apply {
                    begin(optimize = true, maximize = false)
                    onExactSolution("(model)", objective)
                    onComplete(Verdict.OPTIMAL)
                }
            }

            assertEquals("; objective=$objective\nsat\n; optimizationStatus=optimal\n(model)\n", out)
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

    private fun smt(context: VerdictContext, verdict: Verdict): String = capture {
        SmtLibOutput().apply {
            onVerdictContext(context)
            onComplete(verdict)
        }
    }

    @Test
    fun `an exhausted budget should be named as the cause`() {
        val out = smt(VerdictContext(budgetExhausted = true), Verdict.UNKNOWN)
        assertTrue("; budget exhausted" in out, out)
    }

    @Test
    fun `open theory stop reasons should remain distinct`() {
        val timeout = smt(VerdictContext(terminationReason = TerminationReason.Timeout), Verdict.UNKNOWN)
        val fixedWork = smt(
            VerdictContext(terminationReason = TerminationReason.BudgetExhausted),
            Verdict.UNKNOWN,
        )
        val cancelled = smt(VerdictContext(terminationReason = TerminationReason.Cancelled), Verdict.UNKNOWN)

        assertTrue("wall timeout" in timeout, timeout)
        assertTrue("fixed work exhausted" in fixedWork, fixedWork)
        assertTrue("cancelled" in cancelled, cancelled)
    }

    @Test
    fun `SMT interrupted incumbents should retain their objective without an optimum claim`() {
        val reasons = listOf(
            TerminationReason.Timeout,
            TerminationReason.BudgetExhausted,
            TerminationReason.Cancelled,
        )
        for (reason in reasons) {
            val out = capture {
                SmtLibOutput().apply {
                    begin(optimize = true, maximize = false)
                    onSolution("(model)", 7L)
                    onVerdictContext(VerdictContext(terminationReason = reason))
                    onComplete(Verdict.BEST_FOUND)
                }
            }

            assertEquals("; objective=7\nsat\n; optimizationStatus=best-found\n(model)\n", out)
        }
    }

    @Test
    fun `SMT optimization without an incumbent should retain the stop cause`() {
        val out = capture {
            SmtLibOutput().apply {
                begin(optimize = true, maximize = false)
                onVerdictContext(VerdictContext(terminationReason = TerminationReason.Timeout))
                onComplete(Verdict.UNKNOWN)
            }
        }

        assertEquals("unknown\n; optimizationStatus=unknown\n; wall timeout\n", out)
    }

    @Test
    fun `SMT-LIB statistics should distinguish native learning from open theory learning`() {
        val out = capture {
            SmtLibOutput().onStatistics(
                SolveStats(
                    run = RunStats(backend = "backtrack"),
                    search = SearchStats(learnedClauses = SumResult(5.0)),
                    ca = ConflictAnalysisStats(notApplicable = SumResult(3.0), nonAsserting = SumResult(2.0)),
                    openTheoryClauses = OpenTheoryClauseStats(learned = 7),
                ),
                solveTimeMs = 0,
                solutions = 0,
            )
        }

        assertTrue("; learned=5" in out, out)
        assertTrue("; caNotApplicable=3" in out, out)
        assertTrue("; caNonAsserting=2" in out, out)
        assertTrue("; openLearned=7" in out, out)
    }

    @Test
    fun `a pool with nothing that can prove should be named as the cause`() {
        val out = smt(VerdictContext(budgetExhausted = true, completePool = false), Verdict.UNKNOWN)
        assertTrue("no arm in the pool can prove" in out, out)
    }

    @Test
    fun `a decided verdict should carry no reason line`() {
        val out = smt(VerdictContext(), Verdict.SATISFIABLE)
        assertTrue("unknown" !in out, out)
    }

    @Test
    fun `every competition mode should name the cause of an unknown`() {
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
    fun `the flatzinc mode should name the cause as a comment beside its terminator`() {
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
    fun `the cause should not repeat the verdict the status line already gave`() {
        val out = smt(VerdictContext(budgetExhausted = true), Verdict.UNKNOWN)
        assertEquals("unknown", out.lineSequence().first())
        assertEquals(1, out.lineSequence().count { "unknown" in it }, "the verdict is named once: $out")
    }

    @Test
    fun `the flatzinc mode should leave a decided verdict alone`() {
        val out = capture {
            MiniZincOutput().apply {
                onVerdictContext(VerdictContext(budgetExhausted = true))
                onComplete(Verdict.SATISFIABLE)
            }
        }
        assertEquals("==========", out.trim())
    }

    @Test
    fun `the flatzinc mode should report LP work without a backend`() {
        val out = capture {
            MiniZincOutput().onStatistics(
                SolveStats(lp = LpStats(standalonePasses = SumResult(1.0))),
                solveTimeMs = 0L,
                solutions = 0L,
            )
        }

        assertTrue("%%%mzn-stat: lpStandalonePasses=1" in out, out)
    }
}
