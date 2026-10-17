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

class SourceExecutionOutputTest {
    @Test
    fun `native source refutations should retain their verdict without a complete pool`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* x x) -1))").source
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
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* x x) -1))").source
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
