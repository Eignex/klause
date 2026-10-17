package com.eignex.klause.cli

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceExecutionOutputTest {
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

            override fun onSolution(rendered: String, objective: Long?) {
                points += rendered
                assertEquals(null, objective)
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
