package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiniteExecutionTest {
    @Test
    fun `fixed cumulative optimization should automatically use cost aware propagation`() {
        for (hints in listOf(null, SearchHints(emptyList()))) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
                Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), 1),
            ))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1)), null, null, searchHints = hints)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            assertTrue(completed.stats.search.learning.getValue("propagationScheduleDispatches") > 0)
        }
    }

    @Test
    fun `explicit fifo should override automatic fixed cumulative scheduling`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.costAwarePropagation(false)),
            ExecutionOptions.parse(listOf("cost-aware-propagation=false")))) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
                Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), 1),
            ))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1)), null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE, executionOptions = options,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            assertTrue(completed.stats.search.learning.keys.none { it.startsWith("propagationSchedule") })
        }
    }

    @Test
    fun `unqualified fixed models should retain fifo scheduling`() {
        val starts = intArrayOf(0, 1)
        val durations = longArrayOf(2, 2)
        val resources = longArrayOf(1, 1)
        for ((optimize, factor) in listOf(
            false to Cumulative(starts, durations, resources, 1),
            true to null,
            true to Cumulative(starts, durations, resources, 1, presents = intArrayOf(0, 0)),
            true to Cumulative(starts, durations, resources, 2, capacityVar = 2),
            true to Cumulative(starts, durations, resources, 1, durationVars = intArrayOf(2, 2)),
            true to Cumulative(starts, durations, resources, 2, resourceVars = intArrayOf(2, 2)),
        )) {
            val problem = Problem(1, 3, Array(3) { if (it == 2) IntDomain(1, 2) else IntDomain(0, 4) },
                listOfNotNull(factor).toTypedArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1, 0)).takeIf { optimize }, null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertTrue(completed.stats.search.learning.keys.none { it.startsWith("propagationSchedule") })
        }
    }

    @Test
    fun `typed and parsed proof shares should execute a curated finite optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProofShare(0.75)),
            ExecutionOptions.parse(listOf("portfolio-proof-share=0.75")))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val shape = FiniteSolveShape(problem, true, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(true, result.completeSearchAdmitted)
        }
    }

    @Test
    fun `proof share should reject values outside the existing floor and full allocation`() {
        for (share in listOf(-1.0, 0.49, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProofShare(share)),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `proof share should reject explicit recipe ownership`() {
        for (selection in listOf("bt-arm=satOptimized", "arms=1", "var-selector=vsids")) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-proof-share=0.75", selection)),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `proof share should reject satisfaction parallel and heuristic execution`() {
        for ((optimize, cores, engine) in listOf(Triple(false, 1, FiniteEngine.MIXED),
            Triple(true, 2, FiniteEngine.MIXED), Triple(true, 1, FiniteEngine.LOCAL_SEARCH))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProofShare(0.75)),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
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
