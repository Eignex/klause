package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.presolve.PresolveBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourcePreparationExecutionTest {
    @Test
    fun `finite diagnostic requests should stop before publishing a witness`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(ExecutionOption.dryRunPresolve(true)))
        var points = 0

        val result = SourceSetup.execute(request,
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ points++ }, {})))

        assertIs<FiniteSolveOutcome.PreparedOnly>(assertIs<SourceExecutionResult.Finite>(result).result.outcome)
        assertTrue(request.prepareOnly)
        assertEquals(0, points)
    }

    @Test
    fun `linear diagnostics should retain preparation without consuming the node allowance`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val original = (invocation.engineRoute as SourceProblemRoute.OpenTheory).request.model
        val budget = NodeBudget(0)
        val request = SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.dryRunPresolve(true)), nodeBudget = budget)
        var points = 0

        val result = assertIs<SourceExecutionResult.Prepared>(SourceSetup.execute(request,
            SourceExecutionCallbacks(onLinearIncumbent = { points++ })))

        assertSame(invocation, result.invocation)
        assertSame(original, result.originalProblem)
        assertEquals(1, result.result.model.numRealVars)
        assertFalse(result.result.infeasible)
        assertEquals(0L, budget.spent)
        assertEquals(0, points)
    }

    @Test
    fun `linear diagnostics should retain the spent phase allowance`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val budget = PresolveBudget(7).apply { charge(7) }
        val request = SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.dryRunPresolve(true)), presolveBudget = budget)

        assertIs<SourceExecutionResult.Prepared>(SourceSetup.execute(request))

        assertSame(budget, request.presolveBudget)
        assertEquals(7L, budget.allowance)
        assertTrue(budget.spent() >= 7)
    }

    @Test
    fun `an application finite factory should retain the diagnostic stop`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation,
            ExecutionOptions(ExecutionOption.dryRunPresolve(true)), finiteRequest = { shape ->
                SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.BACKTRACK)
            })
        var points = 0

        val result = SourceSetup.execute(request,
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ points++ }, {})))

        assertIs<FiniteSolveOutcome.PreparedOnly>(assertIs<SourceExecutionResult.Finite>(result).result.outcome)
        assertEquals(0, points)
    }

    @Test
    fun `linear enumeration should decline before publishing a witness`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (> x 0))").source,
            ExecutionOperation.ENUMERATE, FiniteEngine.BACKTRACK,
        ))
        var points = 0

        assertFailsWith<PipelineConfigException> {
            SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions()),
                SourceExecutionCallbacks(onLinearIncumbent = { points++ }))
        }

        assertEquals(0, points)
    }

    @Test
    fun `finite enumeration should retain both original Boolean assignments`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool)").source,
            ExecutionOperation.ENUMERATE, FiniteEngine.FIXED,
        ))
        val values = mutableSetOf<Boolean>()

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions()),
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ values += it.boolValue(0) }, {})))

        assertIs<FiniteSolveOutcome.Completed>(assertIs<SourceExecutionResult.Finite>(result).result.outcome)
        assertEquals(setOf(false, true), values)
    }
}
