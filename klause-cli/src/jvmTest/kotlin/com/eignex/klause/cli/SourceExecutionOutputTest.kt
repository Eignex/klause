package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryExecution
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceExecutionOutputTest {
    @Test
    fun `unchanged open preparation should render effort fields`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val solvable = preparedSourceSolvable(invocation, { error("open source is not finite") }, { "point" })
        val common = CommonOptions().apply {
            engine = "cp"
            statistics = true
            presolve = "off,+strengthen"
        }
        var observed: SolveStats? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) { observed = stats }
        }

        SolveCore.solve(solvable, common, output)

        val pairs = presolveStatPairs(assertNotNull(observed)).toMap()
        assertEquals("off", pairs["presolveEmphasis"])
        assertEquals("1", pairs["presolveCalls_strengthen"])
        assertTrue(assertNotNull(pairs["presolveWork"]).toLong() > 0)
        assertNotNull(pairs["presolvePreparationMs"])
        assertNotNull(pairs["presolveWorkAllowance"])
        assertNotNull(pairs["presolveRoundEntries"])
    }

    @Test
    fun `linear source output should retain a disabled preparation ledger`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val common = CommonOptions().apply { engine = "cp"; statistics = true; presolve = "off" }
        val solvable = linearSourceSolvable(source, common, { "point" })
        var observed: SolveStats? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) { observed = stats }
        }

        SolveCore.solve(solvable, common, output)

        val pairs = presolveStatPairs(assertNotNull(observed)).toMap()
        assertEquals("off", pairs["presolveEmphasis"])
        assertNotNull(pairs["presolvePreparationMs"])
        assertNotNull(pairs["presolveWork"])
        assertNotNull(pairs["presolveWorkAllowance"])
        assertEquals("0", pairs["presolveRoundEntries"])
    }

    @Test
    fun `native source refutations should retain their verdict without a complete pool`() {
        val source = SmtLib.parse(
            "(set-logic QF_NRA) (declare-const x Real) (assert (< (* x x) 0))",
        ).source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.LOCAL_SEARCH,
        ))
        val rendering = SourceRendering.ExactSource(invocation) { error("a refutation has no witness") }
        val execution = PolynomialTheoryExecution(OpenTheoryResult.Unsat(SolveStats.EMPTY), PolynomialTheoryStats())
        var completed: Verdict? = null
        var verdictContext: VerdictContext? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onVerdictContext(context: VerdictContext) { verdictContext = context }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        reportNativeSatisfaction(execution, rendering, output, false, 0L, completePool = false)

        assertEquals(Verdict.UNSATISFIABLE, completed)
        assertEquals(false, verdictContext?.completePool)
        assertEquals(null, verdictContext?.terminationReason)
    }

    @Test
    fun `native incomplete satisfaction should retain its stop reason`() {
        val source = SmtLib.parse(
            "(set-logic QF_NRA) (declare-const x Real) (assert (< (* x x) 0))",
        ).source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.LOCAL_SEARCH,
        ))
        val rendering = SourceRendering.ExactSource(invocation) { error("an incomplete result has no witness") }
        val execution = PolynomialTheoryExecution(
            OpenTheoryResult.Unknown(TerminationReason.BudgetExhausted, SolveStats.EMPTY), PolynomialTheoryStats(),
        )
        var completed: Verdict? = null
        var verdictContext: VerdictContext? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onVerdictContext(context: VerdictContext) { verdictContext = context }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        reportNativeSatisfaction(execution, rendering, output, false, 0L, completePool = false)

        assertEquals(Verdict.UNKNOWN, completed)
        assertEquals(TerminationReason.BudgetExhausted, verdictContext?.terminationReason)
    }

    @Test
    fun `numerical satisfaction should publish a witness without an objective`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        assertEquals(SourceExecutionKind.NUMERICAL, SourceSetup.executionKind(invocation))
        val solvable = preparedSourceSolvable(invocation, { error("numerical source is not finite") }, { "point" })
        val common = CommonOptions().apply { engine = "cp" }
        val points = mutableListOf<String>()
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean =
                error("satisfaction has no source objective")

            override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) {
                points += rendered
                assertEquals(null, objective)
                assertEquals(null, continuousObjective)
            }

            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(listOf("point"), points)
        assertEquals(Verdict.SATISFIABLE, completed)
    }
}
