package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class LinearTraversalTest(private val traversal: LinearTraversal) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun traversals(): List<LinearTraversal> = LinearTraversal.entries
    }

    @Test
    fun `bounded source traversal should preserve original maximization units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ofLong(3))),
                upper = SemanticRational.ofLong(2))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                constant = SemanticRational.ofLong(7), sense = ObjectiveSense.MAXIMIZE),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = true, linearTraversal = traversal,
        ))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions()))

        assertSame(source, result.linearSource?.source)
        assertEquals(LinearSourceConclusion.OPTIMAL, result.linearSource?.conclusion)
        assertEquals(BigFraction.ofLong(7), result.linearSource?.incumbent?.objective)
        assertEquals(listOf(BigFraction.ZERO), result.linearSource?.incumbent?.values)
        if (traversal == LinearTraversal.NUMERICAL) assertIs<SourceExecutionResult.Numerical>(result)
        else assertIs<SourceExecutionResult.Finite>(result)
    }

}
