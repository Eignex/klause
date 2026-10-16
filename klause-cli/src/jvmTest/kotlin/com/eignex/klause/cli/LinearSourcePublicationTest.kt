package com.eignex.klause.cli

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.LinearSourceConclusion
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearSourcePublicationTest {
    @Test
    fun `a stopped source preparation should report unknown without requiring a completed binding`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("n", true)), emptyList())
        val common = CommonOptions().apply { engine = "fixed"; deadlineAtMs = nowMillis() - 1L }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            error("a stopped preparation published a point")
        })
        var completed: Verdict? = null
        var retained: SourceExecutionResult? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onLinearSourceResult(result: SourceExecutionResult) { retained = result }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        assertIs<SourceExecutionResult.Declined>(retained)
        assertEquals(Verdict.UNKNOWN, completed)
    }

    @Test
    fun `numerical maximization should retain native payload beside original published cost`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(2))),
                upper = SemanticRational.ofLong(7))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                SemanticRational.of(BIG_ONE, bigIntOf(10)), ObjectiveSense.MAXIMIZE),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )).resolve(),
        )
        val common = CommonOptions().apply { engine = "fixed" }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            "n=${it.values.single()}"
        })
        val values = mutableListOf<BigFraction>()
        var retained: SourceExecutionResult? = null
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertEquals("n=3", rendered)
                values += assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onLinearSourceResult(result: SourceExecutionResult) { retained = result }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(listOf(BigFraction.ofLong(31) * BigFraction.ofLong(10).reciprocal()), values)
        assertEquals(BigFraction.ofLong(31), assertIs<SourceExecutionResult.Numerical>(retained).result.value)
        assertEquals(Verdict.OPTIMAL, completed)
    }

    @Test
    fun `a nominal optimum should not become an optimum of tolerance acceptance`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true), LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ofLong(3))),
                lower = SemanticRational.ONE, upper = SemanticRational.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )).resolve(),
        )
        val common = CommonOptions().apply { engine = "fixed" }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            "x=${it.values[1]}"
        })
        val values = mutableListOf<BigFraction>()
        var retained: SourceExecutionResult? = null
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                values += assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onLinearSourceResult(result: SourceExecutionResult) { retained = result }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(listOf(BigFraction.ofLong(3).reciprocal()), values)
        val result = assertIs<SourceExecutionResult.Numerical>(retained)
        assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim)
        assertEquals(LinearSourceConclusion.SATISFIABLE, result.linearSource?.conclusion)
        assertEquals(Verdict.BEST_FOUND, completed)
    }

    @Test
    fun `zero numerical work should retain unknown without a source publication`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(2))),
                upper = SemanticRational.ofLong(7))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                sense = ObjectiveSense.MAXIMIZE),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )).resolve(),
        )
        val common = CommonOptions().apply { engine = "fixed"; engineParams += "node-limit=0" }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            error("a stopped execution published a point")
        })
        var completed: Verdict? = null
        var reason: TerminationReason? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onComplete(verdict: Verdict) { completed = verdict }
            override fun onVerdictContext(context: VerdictContext) { reason = context.terminationReason }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(Verdict.UNKNOWN, completed)
        assertEquals(TerminationReason.BudgetExhausted, reason)
    }

    @Test
    fun `a source rendering veto should withhold the optimum marker`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ONE, SemanticRational.ONE)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val common = CommonOptions().apply { engine = "fixed" }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, { "n=1" })
        var attempted = false
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                attempted = true
                return false
            }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        assertTrue(attempted)
        assertEquals(Verdict.UNKNOWN, completed)
    }

    @Test
    fun `an unknown linear source parameter should reject before execution`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)), emptyList(),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )).resolve(),
        )
        val common = CommonOptions().apply { engine = "fixed"; engineParams += "unknown-source-knob=1" }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            error("an invalid invocation published a point")
        })

        assertFailsWith<CliUsageException> { SolveCore.solve(solvable, common, SmtLibOutput()) }
    }
}
