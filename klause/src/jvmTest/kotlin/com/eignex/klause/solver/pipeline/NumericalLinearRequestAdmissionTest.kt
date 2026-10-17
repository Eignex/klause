package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NumericalLinearRequestAdmissionTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)
    }

    @Test
    fun `open numerical sources should decline unavailable heuristic workers before publication`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.SATISFY, engine,
        ))
        val budget = NodeBudget(3)
        var publications = 0
        val request = SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = budget)

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            request, SourceExecutionCallbacks(onLinearIncumbent = { publications++ }),
        ))

        assertEquals(TerminationReason.Unsupported, result.result.reason)
        assertNull(result.result.incumbent)
        assertNull(result.result.terminal)

        assertEquals(SourceExecutionKind.NUMERICAL, SourceSetup.executionKind(invocation))
        assertFalse(assertNotNull(invocation.admission).runnable)
        assertEquals(0L, budget.spent)
        assertEquals(0, publications)
    }
}
