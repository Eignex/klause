package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
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
import kotlin.time.Duration.Companion.seconds

class FiniteExecutionTest {
    @Test
    fun `Boolean proof controls should reach actual source portfolio probing`() {
        for ((enabled, options) in listOf(
            false to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "boolean-proof-tasks=false")),
            true to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "boolean-proof-tasks=true")),
            true to ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")), ExecutionOption.booleanProofTasks(true)),
        )) {
            val problem = Problem(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ))
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            val observations = completed.stats.portfolio.booleanProof

            assertEquals(if (enabled) 1 else 0, observations.size)
            if (enabled) {
                assertNull(observations.single().decline)
                assertEquals(1, observations.single().probeSegments)
                assertEquals(0, observations.single().tasks)
                assertTrue(observations.single().workerWork >= observations.single().probeWork)
            }
        }
    }

    @Test
    fun `unsupported source models should record a Boolean proof decline and keep ordinary solving`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail",
                "boolean-proof-tasks=true")),
        )

        val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
        val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
        val observation = completed.stats.portfolio.booleanProof.single()

        assertEquals("model-ineligible", observation.decline)
        assertEquals(0, observation.tasks)
        assertEquals(0, observation.probeSegments)
    }

    @Test
    fun `Boolean proof controls should reject optimization and parallel lanes`() {
        for ((optimize, cores) in listOf(true to 1, false to 2)) {
            val problem = Problem(2, 0, emptyArray(), emptyArray())
            val objective = LinearObjective(boolWeights = longArrayOf(1L, 0L)).takeIf { optimize }
            val shape = FiniteSolveShape(problem, optimize, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE, cores = cores,
                executionOptions = ExecutionOptions.parse(listOf("boolean-proof-tasks=true")),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `leading probe budgets should require a containing time deadline`() {
        for (policy in listOf("leader-half", "lp-transfer")) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProbes(policy)),
                nodeBudget = NodeBudget(8),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `typed and parsed leading probe budgets should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProbes("leader-half")),
            ExecutionOptions.parse(listOf("portfolio-probes=leader-half")),
            ExecutionOptions.parse(listOf("portfolio-probes=leader-half", "portfolio-lp-start=after-probes")),
            ExecutionOptions(ExecutionOption.portfolioProbes("lp-transfer")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer", "portfolio-lp-start=after-probes")))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = Cancellation.after(1.seconds),
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `leading probe budgets should reject unknown policies explicit recipes and work schedules`() {
        for (settings in listOf(listOf("portfolio-probes=never"),
            listOf("portfolio-probes=leader-half", "arms=2"),
            listOf("portfolio-probes=leader-half", "bt-arm=satOptimized"),
            listOf("portfolio-probes=leader-half", "portfolio-work=100"),
            listOf("portfolio-probes=lp-transfer", "arms=2"),
            listOf("portfolio-probes=lp-transfer", "bt-arm=satOptimized"),
            listOf("portfolio-probes=lp-transfer", "portfolio-work=100"))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings),
                cancellation = Cancellation.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `leading probe budgets should reject unsupported operations lanes and engines`() {
        for ((optimize, cores, engine) in listOf(Triple(false, 1, FiniteEngine.MIXED),
            Triple(true, 2, FiniteEngine.MIXED), Triple(true, 1, FiniteEngine.BACKTRACK))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProbes("leader-half")),
                cancellation = Cancellation.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `leading probe budgets should reject Boolean sources`() {
        for (policy in listOf("leader-half", "lp-transfer")) {
            val shape = FiniteSolveShape(Problem(1, 0, emptyArray(), emptyArray()), true, false, null,
                LinearObjective(longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProbes(policy)),
                cancellation = Cancellation.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `typed and parsed proof worker scopes should execute curated optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProofWorkers("complete")),
            ExecutionOptions.parse(listOf("portfolio-proof-workers=complete")),
            ExecutionOptions.parse(listOf("portfolio-proof-workers=retained")))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(true, result.completeSearchAdmitted)
        }
    }

    @Test
    fun `proof worker scopes should reject unknown policies and explicit recipes`() {
        for (settings in listOf(listOf("portfolio-proof-workers=never"),
            listOf("portfolio-proof-workers=complete", "arms=2"),
            listOf("portfolio-proof-workers=complete", "bt-arm=satOptimized"),
            listOf("portfolio-proof-workers=retained", "var-selector=vsids"))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `complete proof coverage should reject unsupported operations lanes and engines`() {
        for ((optimize, cores, engine) in listOf(Triple(false, 1, FiniteEngine.MIXED),
            Triple(true, 2, FiniteEngine.MIXED), Triple(true, 1, FiniteEngine.LOCAL_SEARCH))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProofWorkers("complete")),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `typed and parsed deferred LP controls should execute a curated finite integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioLpStart("after-probes")),
            ExecutionOptions.parse(listOf("portfolio-lp-start=after-probes")))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(true, result.completeSearchAdmitted)
        }
    }

    @Test
    fun `deferred LP controls should reject unknown policies and explicit recipe ownership`() {
        for (settings in listOf(listOf("portfolio-lp-start=never"),
            listOf("portfolio-lp-start=after-probes", "arms=2"),
            listOf("portfolio-lp-start=after-probes", "bt-arm=satOptimized"))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `deferred LP controls should reject unsupported operations lanes and engines`() {
        for ((optimize, cores, engine) in listOf(Triple(false, 1, FiniteEngine.MIXED),
            Triple(true, 2, FiniteEngine.MIXED), Triple(true, 1, FiniteEngine.BACKTRACK))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioLpStart("after-probes")),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `deferred LP controls should reject Boolean sources`() {
        val shape = FiniteSolveShape(Problem(1, 0, emptyArray(), emptyArray()), true, false, null,
            LinearObjective(longArrayOf(1)), 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions(ExecutionOption.portfolioLpStart("after-probes")),
        )

        assertFailsWith<PipelineConfigException> {
            FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
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
