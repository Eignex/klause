package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FiniteExecutionTest {
    @Test
    fun `a mixed request with only heuristic arms should retain its resolved admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(
            ExecutionOption.backtrackArms(0), ExecutionOption.localSearchArms(1),
        ))

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertEquals(false, result.result.completeSearchAdmitted)
        assertEquals(false, result.completeSearchAdmitted)
        assertEquals(FiniteSolveVerdict.SAT, assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict)
    }

    @Test
    fun `preparation diagnostics should have no resolved search admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(ExecutionOption.dryRunPresolve(true)))

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertIs<FiniteSolveOutcome.PreparedOnly>(result.result.outcome)
        assertNull(result.result.completeSearchAdmitted)
        assertNull(result.completeSearchAdmitted)
    }

    @Test
    fun `containing cancellation before preparation should not invent a worker admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))
        var cancelled = false
        val request = SourceExecutionRequest(invocation, ExecutionOptions(), cancellation = Cancellation { cancelled })
        cancelled = true

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict)
        assertNull(result.completeSearchAdmitted)
    }
}
