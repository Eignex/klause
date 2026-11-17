package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FiniteExecutionSchedulingTest {
    @Test
    fun `initial probe policies should require a containing time deadline`() {
        for (policy in listOf("leader-half", "lp-transfer", "heuristic-second", "family-work", "heuristic-work",
            "alns-work")) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
    fun `typed initial probe policies should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProbes("leader-half")),
            ExecutionOptions(ExecutionOption.portfolioProbes("lp-transfer")),
            ExecutionOptions(ExecutionOption.portfolioProbes("heuristic-second")),
            ExecutionOptions(ExecutionOption.portfolioProbes("family-work")),
            ExecutionOptions(ExecutionOption.portfolioProbes("heuristic-work")),
            ExecutionOptions(ExecutionOption.portfolioProbes("alns-work")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds),
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `parsed initial probe policies should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions.parse(listOf("portfolio-probes=leader-half")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second")),
            ExecutionOptions.parse(listOf("portfolio-probes=family-work")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-work")),
            ExecutionOptions.parse(listOf("portfolio-probes=alns-work")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds),
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `initial probe policies should compose with deferred LP starts`() {
        for (options in listOf(
            ExecutionOptions.parse(listOf("portfolio-probes=leader-half", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=family-work", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-work", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=alns-work", "portfolio-lp-start=after-probes")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds),
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `paid heuristic probes should compose with buffered publication`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProbes("family-work"),
            ExecutionOption.portfolioPublications("segment")),
            ExecutionOptions.parse(listOf("portfolio-probes=family-work", "portfolio-publications=segment")),
            ExecutionOptions(ExecutionOption.portfolioProbes("heuristic-work"),
                ExecutionOption.portfolioPublications("segment")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-work", "portfolio-publications=segment")),
            ExecutionOptions(ExecutionOption.portfolioProbes("alns-work"),
                ExecutionOption.portfolioPublications("segment")),
            ExecutionOptions.parse(listOf("portfolio-probes=alns-work", "portfolio-publications=segment")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds),
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `initial probe policies should reject unknown policies explicit recipes and work schedules`() {
        for (settings in listOf(listOf("portfolio-probes=never"),
            listOf("portfolio-probes=leader-half", "arms=2"),
            listOf("portfolio-probes=leader-half", "bt-arm=satOptimized"),
            listOf("portfolio-probes=leader-half", "portfolio-work=100"),
            listOf("portfolio-probes=lp-transfer", "arms=2"),
            listOf("portfolio-probes=lp-transfer", "bt-arm=satOptimized"),
            listOf("portfolio-probes=lp-transfer", "portfolio-work=100"),
            listOf("portfolio-probes=heuristic-second", "arms=2"),
            listOf("portfolio-probes=heuristic-second", "bt-arm=satOptimized"),
            listOf("portfolio-probes=heuristic-second", "portfolio-work=100"),
            listOf("portfolio-probes=family-work", "arms=2"),
            listOf("portfolio-probes=family-work", "bt-arm=satOptimized"),
            listOf("portfolio-probes=family-work", "portfolio-work=100"),
            listOf("portfolio-probes=heuristic-work", "arms=2"),
            listOf("portfolio-probes=heuristic-work", "bt-arm=satOptimized"),
            listOf("portfolio-probes=heuristic-work", "portfolio-work=100"),
            listOf("portfolio-probes=alns-work", "arms=2"),
            listOf("portfolio-probes=alns-work", "bt-arm=satOptimized"),
            listOf("portfolio-probes=alns-work", "portfolio-work=100"))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings),
                cancellation = ExecutionControl.after(1.seconds),
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProbes("leader-half")),
                cancellation = ExecutionControl.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `integer probe policies should reject Boolean sources`() {
        for (policy in listOf("leader-half", "lp-transfer", "family-work", "heuristic-work",
            "alns-work")) {
            val shape = FiniteSolveShape(Problem.of(1, 0, emptyArray(), emptyArray()), true, false, null,
                LinearObjective(longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions(ExecutionOption.portfolioProbes(policy)),
                cancellation = ExecutionControl.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `early heuristic probes should execute Boolean and mixed finite optimization`() {
        for (intCount in listOf(0, 1)) {
            val problem = Problem.of(1, intCount, Array(intCount) { IntDomain(0, 1) }, emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(boolWeights = longArrayOf(1), intCoefficients = LongArray(intCount) { 1L }),
                null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second")),
                cancellation = ExecutionControl.after(1.seconds), nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertEquals(FiniteSolveVerdict.OPTIMAL, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        }
    }

    @Test
    fun `early heuristic probes should retain the source annotation worker`() {
        val labels = listOf("ordinary", "heuristic-second").map { policy ->
            val problem = Problem.of(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val hints = SearchHints(listOf(SearchGroup(intArrayOf(0), intArrayOf(0))))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(longArrayOf(1), longArrayOf(1)), null, null, searchHints = hints)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-probes=$policy")),
                cancellation = ExecutionControl.after(1.seconds), nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            completed.stats.portfolio.arms.map { it.label }
        }

        assertTrue("bt/annotation" in labels[0])
        assertEquals(labels[0], labels[1])
    }

    @Test
    fun `proof coverage controls should retain the source annotation worker`() {
        val labels = listOf(emptyList(), listOf("portfolio-proof-workers=complete"),
            listOf("portfolio-proof-workers=retained"), listOf("portfolio-proof-share=0.75")).map { settings ->
            val problem = Problem.of(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val hints = SearchHints(listOf(SearchGroup(intArrayOf(0), intArrayOf(0))))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(longArrayOf(1), longArrayOf(1)), null, null, searchHints = hints)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings), nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            completed.stats.portfolio.arms.map { it.label }
        }

        assertTrue("bt/annotation" in labels[0])
        labels.drop(1).forEach { assertEquals(labels[0], it) }
    }

    @Test
    fun `typed and parsed proof worker scopes should execute curated optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProofWorkers("complete")),
            ExecutionOptions.parse(listOf("portfolio-proof-workers=complete")),
            ExecutionOptions.parse(listOf("portfolio-proof-workers=retained")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
        val shape = FiniteSolveShape(Problem.of(1, 0, emptyArray(), emptyArray()), true, false, null,
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
}
