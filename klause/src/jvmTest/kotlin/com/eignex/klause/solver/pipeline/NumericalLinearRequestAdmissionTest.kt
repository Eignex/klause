package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.result.TerminationReason
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class NumericalLinearRequestAdmissionTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)
    }

    @Test
    fun `open numerical sources should admit heuristic workers and publish checked incumbents`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY,
            engine,
        )
        )
        val budget = NodeBudget(Long.MAX_VALUE)
        var publications = 0
        val request = SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = budget)

        val result = assertIs<SourceExecutionResult.Numerical>(
            SourceSetup.execute(
            request,
            SourceExecutionCallbacks(onLinearIncumbent = { publications++ }),
        )
        )

        assertEquals(0L, assertNotNull(result.result.incumbent).intValue(0))
        assertNull(result.result.terminal)
        assertNull(result.result.bound)

        assertEquals(SourceExecutionKind.NUMERICAL, SourceSetup.executionKind(invocation))
        assertTrue(assertNotNull(invocation.admission).runnable)
        assertTrue(budget.spent > 0)
        assertEquals(1, publications)
    }

    @Test
    fun `an exhausted allowance should withhold numerical heuristic publication after admission`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, engine))
        val budget = NodeBudget(0)
        var publications = 0

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = budget),
            SourceExecutionCallbacks(onLinearIncumbent = { publications++ })))

        assertEquals(TerminationReason.BudgetExhausted, result.result.reason)
        assertNull(result.result.incumbent)
        assertEquals(0L, budget.spent)
        assertEquals(0, publications)
    }
}
