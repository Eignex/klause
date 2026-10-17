package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class FiniteExecutionMetadataTest(
    private val engine: FiniteEngine,
    private val options: ExecutionOptions,
    private val expected: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index} {0}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(FiniteEngine.FIXED, ExecutionOptions(), true),
            arrayOf(FiniteEngine.BACKTRACK, ExecutionOptions(), true),
            arrayOf(FiniteEngine.LOCAL_SEARCH, ExecutionOptions(), false),
            arrayOf(FiniteEngine.ALNS, ExecutionOptions(), false),
            arrayOf(FiniteEngine.MIXED, ExecutionOptions(
                ExecutionOption.backtrackArms(1), ExecutionOption.localSearchArms(1),
            ), true),
            arrayOf(FiniteEngine.MIXED, ExecutionOptions(
                ExecutionOption.backtrackArms(0), ExecutionOption.localSearchArms(1),
            ), false),
        )
    }

    @Test
    fun `finite satisfaction metadata should follow the selected worker mix`() {
        val shape = FiniteSolveShape(Problem(1, 0, emptyArray(), emptyArray()), false, false, null, null, null, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioArms = 1).finiteRequest(
            shape, engine, presolveConfig = PresolveConfig.NONE,
            executionOptions = options, nodeBudget = NodeBudget(4),
        )

        val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

        assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(expected, result.completeSearchAdmitted)
    }

    @Test
    fun `finite optimization metadata should follow the selected worker mix`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val shape = FiniteSolveShape(problem, true, false, null, objective, 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioArms = 1).finiteRequest(
            shape, engine, presolveConfig = PresolveConfig.NONE,
            executionOptions = options, nodeBudget = NodeBudget(4),
        )

        val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

        assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(expected, result.completeSearchAdmitted)
    }
}
