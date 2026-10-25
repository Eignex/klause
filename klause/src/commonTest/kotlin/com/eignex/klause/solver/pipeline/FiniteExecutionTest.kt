package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FiniteExecutionTest {
    @Test
    fun `default sequential mixed placement should retain objective coordinates`() {
        val modes = listOf(
            Triple(FiniteEngine.MIXED, 1, false) to 1L,
            Triple(FiniteEngine.MIXED, 2, false) to 0L,
            Triple(FiniteEngine.BACKTRACK, 1, false) to 0L,
            Triple(FiniteEngine.MIXED, 1, true) to 0L,
        )
        for (placement in listOf(false, true)) {
            for ((mode, coefficient) in modes) {
                val (engine, cores, explicit) = mode
                val reader = if (placement) {
                    Diffn(intArrayOf(0, 2), intArrayOf(3, 4), longArrayOf(1, 1), longArrayOf(1, 1))
                } else AllDifferent(intArrayOf(0, 2))
                val source = Problem(0, 5, arrayOf(
                    IntDomain(0, 5), IntDomain(1, 6), IntDomain(0, 5), IntDomain(0, 0), IntDomain(0, 0),
                ), listOf(Linear(longArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 1L), reader))
                val objective = LinearObjective(intCoefficients = longArrayOf(0, 1, 0, 0, 0))
                val request = FiniteSolveRequest(
                    shape = FiniteSolveShape(source, true, false, null, objective, 1, null),
                    engine = engine, presolveConfig = PresolveConfig.parse("off,+affine"),
                    explicitPresolveConfig = explicit, solutionSetSensitive = false,
                    cancellation = Cancellation.Never, presolveBudget = null, cores = cores,
                    executionOptions = ExecutionOptions(), randomSeed = 1L, defaultArms = 6, lpConfig = LpConfig.OFF,
                    zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null,
                    solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                    deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null, prepareOnly = true,
                )

                val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

                assertEquals(
                    if (placement) coefficient else 0L,
                    assertNotNull(result.preparation.objective).intCoefficientValues[1],
                    "placement=$placement engine=$engine cores=$cores explicit=$explicit",
                )
            }
        }
    }

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
