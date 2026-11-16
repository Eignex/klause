package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.flatZincSemantics
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareFiniteCompatibility
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.formats.smtlib.smtLibSemantics
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.ConflictAnalysisStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import java.io.File
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

        val out = captureStdout {
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
            val out = captureStdout {
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
                val out = captureStdout {
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
                semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous, undeclaredReals = bounds,
                    feasibilityTolerance = SourceTolerance()),
            )
            val result = BacktrackSolver.source(program.problem).optimizeOpen(
                LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            )
            val linear = assertNotNull(assertIs<OpenTheoryExecution.Optimize>(result).result.linearResult)

            val out = captureStdout {
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
    fun `the exact flag should refute a FlatZinc float bound that only tolerance admits`() {
        val model = "var 0.0..2.0: y :: output_var; var 200..200: n; var 0.0..200.0: nf; " +
            "constraint int2float(n,nf); constraint float_lin_eq([0.01,-1.0],[nf,y],0.0); solve satisfy;"
        for ((args, terminal) in listOf(
            emptyArray<String>() to "==========", arrayOf("--exact") to "=====UNSATISFIABLE=====",
        )) {
            val out = solveFlatZinc(model, *args)

            assertTrue(terminal in out.lines(), out)
        }
    }

    // The CLI output of a FlatZinc [model] solved by the single-threaded backtrack engine under [args].
    private fun solveFlatZinc(model: String, vararg args: String): String {
        val source = File.createTempFile("float-relation", ".fzn").apply { writeText(model) }
        try {
            val common = CommonOptions().apply {
                engine = "cp"
                parallel = 1
                randomSeed = 17
            }
            parseArgs(arrayOf(*args), commonFlagSpecs(common)) {}
            val session = MiniZincMode.newSession()
            val solvable = session.load(source.absolutePath, common)
            return captureStdout { SolveCore.solve(solvable, common, session.output(common)) }
        } finally {
            source.delete()
        }
    }

    private val strictGapRows = "var 0.0..1.0: x :: output_var; var 0.0..1.0: y :: output_var; var 0.0..1.0: p; " +
        "constraint float_lt(x,y); constraint float_lin_lt([1.0,-1.0],[y,x],0.00000015);"

    private fun strictGapTerminals(model: String) =
        mapOf("default" to emptyArray<String>(), "exact" to arrayOf("--exact")).mapValues { (_, args) ->
            solveFlatZinc(model, *args).lines().first { it == "==========" || it == "=====UNSATISFIABLE=====" }
        }

    @Test
    fun `the exact flag should accept a FlatZinc strict row gap below the absolute slack`() {
        val terminals = strictGapTerminals("$strictGapRows solve satisfy;")

        assertEquals(mapOf("default" to "=====UNSATISFIABLE=====", "exact" to "=========="), terminals)
    }

    @Test
    fun `the exact flag should accept a strict row gap below the absolute slack beside a product`() {
        val terminals = strictGapTerminals("$strictGapRows constraint float_times(x,y,p); solve satisfy;")

        assertEquals(mapOf("default" to "=====UNSATISFIABLE=====", "exact" to "=========="), terminals)
    }

    @Test
    fun `a FlatZinc strict lower bound should attain its minimum at the absolute slack by default`() {
        val out = solveFlatZinc("var 0.0..1.0: x :: output_var; constraint float_lt(0.0,x); solve minimize x;")

        val value = out.lines().first { it.startsWith("x = ") }.removePrefix("x = ").removeSuffix(";").toDouble()
        assertEquals(1e-7, value, 1e-20, out)
        assertTrue("==========" in out.lines() && "unattained-infimum" !in out, out)
    }

    @Test
    fun `incomplete engines should accept a FlatZinc float bound that tolerance admits`() {
        val source = File.createTempFile("rounded-bound", ".fzn").apply {
            writeText("var 0.0..2.0: y :: output_var; var 200..200: n; var 0.0..200.0: nf; " +
                "constraint int2float(n,nf); constraint float_lin_eq([0.01,-1.0],[nf,y],0.0); solve satisfy;")
        }
        try {
            for (name in listOf("localsearch", "alns")) {
                val common = CommonOptions().apply {
                    engine = name
                    parallel = 1
                    randomSeed = 17
                }
                val session = MiniZincMode.newSession()
                val solvable = session.load(source.absolutePath, common)

                val out = captureStdout { SolveCore.solve(solvable, common, session.output(common)) }

                assertTrue("y = 2.0;" in out.lines() && "=====UNSATISFIABLE=====" !in out, "$name: $out")
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun `incomplete engines should not refute a satisfiable float product through the grid`() {
        val source = File.createTempFile("grid-product", ".fzn").apply {
            writeText("var 0.0..2.0: x; var 0.0..2.0: w; var 0.0..4.0: z; " +
                "constraint float_times(x,w,z); constraint float_eq(z,0.3); solve satisfy;")
        }
        try {
            for (name in listOf("localsearch", "alns")) {
                val common = CommonOptions().apply {
                    engine = name
                    parallel = 1
                    randomSeed = 17
                }
                val session = MiniZincMode.newSession()
                val solvable = session.load(source.absolutePath, common)

                val out = captureStdout { SolveCore.solve(solvable, common, session.output(common)) }

                assertTrue("=====UNSATISFIABLE=====" !in out && "klause-float-claim" in out, "$name: $out")
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun `the default engine should decide float product models without the grid`() {
        for ((bounds, terminal) in listOf(
            "" to "==========",
            "constraint float_le(x,0.2); constraint float_le(w,0.5); " to "=====UNSATISFIABLE=====",
        )) {
            val source = File.createTempFile("grid-product", ".fzn").apply {
                writeText("var 0.0..2.0: x; var 0.0..2.0: w; var 0.0..4.0: z; $bounds" +
                    "constraint float_times(x,w,z); constraint float_eq(z,0.3); solve satisfy;")
            }
            try {
                val common = CommonOptions().apply {
                    parallel = 1
                    randomSeed = 17
                }
                val session = MiniZincMode.newSession()
                val solvable = session.load(source.absolutePath, common)

                val out = captureStdout { SolveCore.solve(solvable, common, session.output(common)) }

                assertTrue(terminal in out.lines() && "klause-float-claim" !in out, out)
            } finally {
                source.delete()
            }
        }
    }

    @Test
    fun `a source-accepted grid solution should report satisfiable`() {
        val program = parseFlatZinc("var 0.0..1.0: x :: output_var; " +
            "constraint float_le(x,0.5); solve satisfy;", floatBuckets = 3)
        val invocation = program.prepareFiniteCompatibility(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertNotNull(invocation.floatProjection)
        val solvable = Solvable(invocation.finiteShape(), { writeFlatZincSolution(program.sourceProgram, it) }, null)
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            randomSeed = 17
        }

        val out = captureStdout { SolveCore.solve(solvable, common, MiniZincOutput(floatApproximation = true)) }

        assertTrue("x = " in out, out)
        assertTrue("private finite grid" in out, out)
        assertTrue("==========" in out.lines(), out)
    }

    @Test
    fun `a rounded grid witness should not be published as a source solution`() {
        val program = parseFlatZinc("var 0.0..1.0: x; var 0.0..1.0: y; " +
            "constraint float_eq(x,0.5); constraint float_times(x,x,y); solve satisfy;", floatBuckets = 3)
        val invocation = program.prepareFiniteCompatibility(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertNotNull(invocation.floatProjection)
        val solvable = Solvable(invocation.finiteShape(), { writeFlatZincSolution(program.sourceProgram, it) }, null)
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            randomSeed = 17
        }

        val out = captureStdout { SolveCore.solve(solvable, common, MiniZincOutput(floatApproximation = true)) }

        assertTrue("klause-grid-verdict: SAT" in out, out)
        assertTrue("=====UNKNOWN=====" in out.lines(), out)
        assertTrue("y = 0.0;" !in out && "==========" !in out.lines(), out)
    }

    @Test
    fun `a grid refutation of a satisfiable source should report unknown`() {
        val program = parseFlatZinc("var 0.0..1.0: x; var 0.0..1.0: y; " +
            "constraint float_eq(x,0.25); constraint float_times(x,x,y); solve satisfy;", floatBuckets = 3)
        val invocation = program.prepareFiniteCompatibility(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))
        assertNotNull(invocation.floatProjection)
        val solvable = Solvable(invocation.finiteShape(), { "unexpected grid point" }, null)
        val common = CommonOptions().apply {
            engine = "fixed"
            parallel = 1
            randomSeed = 17
        }

        val out = captureStdout { SolveCore.solve(solvable, common, MiniZincOutput(floatApproximation = true)) }

        assertTrue("=====UNKNOWN=====" in out.lines(), out)
        assertTrue("=====UNSATISFIABLE=====" !in out, out)
        assertTrue("klause-grid-verdict: UNSAT" in out, out)
        assertTrue("unexpected grid point" !in out, out)
    }

    @Test
    fun `a finite grid should withhold an unboundedness claim`() {
        val out = captureStdout { MiniZincOutput(floatApproximation = true).onComplete(Verdict.UNBOUNDED) }

        assertTrue("=====UNKNOWN=====" in out.lines(), out)
        assertTrue("=====UNBOUNDED=====" !in out, out)
    }

    @Test
    fun `an interrupted grid incumbent should withhold an optimum marker`() {
        val out = captureStdout {
            MiniZincOutput(floatApproximation = true).apply {
                onSolution("x = 0.5;\n----------\n", 1L, null)
                onVerdictContext(VerdictContext(budgetExhausted = true))
                onComplete(Verdict.BEST_FOUND)
            }
        }

        assertTrue("x = 0.5;\n----------" in out, out)
        assertTrue("==========" !in out, out)
        assertTrue("private finite grid" in out, out)
    }

    @Test
    fun `SMT output should preserve arbitrary integers and rational objectives`() {
        for ((numerator, denominator) in listOf(
            "9223372036854775808" to 1,
            "9007199254740993" to 1,
            "-1" to 3,
        )) {
            val objective = BigFraction.of(parseBigInt(numerator), bigIntOf(denominator))
            val out = captureStdout {
                SmtLibOutput().apply {
                    begin(optimize = true, maximize = false)
                    onExactSolution("(model)", objective)
                    onComplete(Verdict.OPTIMAL)
                }
            }

            assertEquals("; objective=$objective\nsat\n(model)\nsat\n; optimizationStatus=optimal\n", out)
        }
    }

    private fun smt(context: VerdictContext, verdict: Verdict): String = captureStdout {
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
            val out = captureStdout {
                SmtLibOutput().apply {
                    begin(optimize = true, maximize = false)
                    onSolution("(model)", 7L)
                    onVerdictContext(VerdictContext(terminationReason = reason))
                    onComplete(Verdict.BEST_FOUND)
                }
            }

            assertEquals("; objective=7\nsat\n(model)\nsat\n; optimizationStatus=best-found\n", out)
        }
    }

    @Test
    fun `SMT optimization without an incumbent should retain the stop cause`() {
        val out = captureStdout {
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
        val out = captureStdout {
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
            val out = captureStdout {
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
        val out = captureStdout {
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
        val out = captureStdout {
            MiniZincOutput().apply {
                onVerdictContext(VerdictContext(budgetExhausted = true))
                onComplete(Verdict.SATISFIABLE)
            }
        }
        assertEquals("==========", out.trim())
    }

    @Test
    fun `the flatzinc mode should report LP work without a backend`() {
        val out = captureStdout {
            MiniZincOutput().onStatistics(
                SolveStats(lp = LpStats(standalonePasses = SumResult(1.0))),
                solveTimeMs = 0L,
                solutions = 0L,
            )
        }

        assertTrue("%%%mzn-stat: lpStandalonePasses=1" in out, out)
    }
}
