package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpCertificationMode
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
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
    fun `adaptive companion repair should reject unknown modes and missing completion`() {
        for (settings in listOf("companion-repair=never", "companion-repair=adaptive")) {
            val shape = FiniteSolveShape(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = ExecutionOptions.parse(listOf(settings)),
                nodeBudget = NodeBudget(8))

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `typed companion repair modes should reach completion enabled portfolios`() {
        for (repair in listOf("fixed", "adaptive")) {
            val options = ExecutionOptions(ExecutionOption.companionRepair(repair))
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shape = FiniteSolveShape(model, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = options,
                cancellation = Cancellation.after(1.seconds), nodeBudget = NodeBudget(8))

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}),
                TheoryParams(sourceIncumbentCompletion = true))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `parsed companion repair modes should reach completion enabled portfolios`() {
        for (repair in listOf("fixed", "adaptive")) {
            val options = ExecutionOptions.parse(listOf("companion-repair=$repair"))
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shape = FiniteSolveShape(model, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = options,
                cancellation = Cancellation.after(1.seconds), nodeBudget = NodeBudget(8))

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}),
                TheoryParams(sourceIncumbentCompletion = true))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `typed and parsed publication batching should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioPublications("segment")),
            ExecutionOptions.parse(listOf("portfolio-publications=segment")))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = Cancellation.after(1.seconds), executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `publication batching should reject unknown policies explicit recipes and work schedules`() {
        for (settings in listOf(listOf("portfolio-publications=never"),
            listOf("portfolio-publications=segment", "arms=2"),
            listOf("portfolio-publications=segment", "bt-arm=satOptimized"),
            listOf("portfolio-publications=segment", "portfolio-work=100"))) {
            val shape = FiniteSolveShape(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings), cancellation = Cancellation.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `publication batching should reject unsupported operations lanes and engines`() {
        for ((optimize, cores, engine) in listOf(
            Triple(false, 1, FiniteEngine.MIXED), Triple(true, 2, FiniteEngine.MIXED),
            Triple(true, 1, FiniteEngine.LOCAL_SEARCH), Triple(true, 1, FiniteEngine.BACKTRACK),
        )) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
                cancellation = Cancellation.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `publication batching should reject Boolean sources`() {
        val shape = FiniteSolveShape(Problem(1, 0, emptyArray(), emptyArray()), true, false, null,
            LinearObjective(longArrayOf(1)), 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
            cancellation = Cancellation.after(1.seconds),
        )

        assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
    }

    @Test
    fun `publication batching should require a containing deadline`() {
        val shape = FiniteSolveShape(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
            LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
            nodeBudget = NodeBudget(8),
        )

        assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
    }

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
    fun `funded companion admission should reject missing start contracts`() {
        for (settings in listOf(listOf("companion-admission=never"), listOf("companion-admission=funded"),
            listOf("companion-admission=funded", "companion-after-incumbent=true"),
            listOf("companion-admission=progress-funded"),
            listOf("companion-admission=progress-funded", "companion-after-incumbent=true"))) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings), nodeBudget = NodeBudget(8),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `typed and parsed funded admission should reach the source portfolio`() {
        val variants = listOf("funded", "progress-funded").flatMap { admission ->
            listOf(ExecutionOptions(ExecutionOption.companionAdmission(admission),
                ExecutionOption.companionAfterIncumbent(true)), ExecutionOptions.parse(listOf(
                "companion-admission=$admission", "companion-after-incumbent=true")))
        }
        for (options in variants) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = Cancellation.after(1.seconds), executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `Boolean proof controls should reach actual source portfolio probing`() {
        for ((enabled, options) in listOf(
            true to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail")),
            true to ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail"))),
            false to ExecutionOptions.parse(listOf("arms=2", "bt-arm=clausal-balanced")),
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
    fun `private rebuild budgets should preserve the default clausal source pool`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.privateRebuildBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(0L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=0")),
            ExecutionOptions(ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("learned-memory-scratch-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(0L),
                ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=0", "learned-memory-scratch-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(-1L),
                ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=-1", "learned-memory-scratch-bytes=4194304")),
        )) {
            val problem = Problem(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ))
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            assertEquals(listOf("ls/probsat/plain", "bt/clausal-balanced"),
                completed.stats.portfolio.arms.map { it.label })
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `ordinary private defaults should honor explicit work controls`() {
        for ((enabled, options) in listOf(
            true to ExecutionOptions(ExecutionOption.sliceWork(1_000L)),
            false to ExecutionOptions(ExecutionOption.sliceWork(1_000L), ExecutionOption.privateRebuildWork(0L),
                ExecutionOption.privateRebuildBytes(4_194_304L)),
            false to ExecutionOptions.parse(listOf("slice-work=1000",
                "private-rebuild-work=0", "private-rebuild-bytes=4194304")),
        )) {
            val parity = (0 until 128).flatMap { row ->
                listOf(1, 2, 4, 7).map { mask ->
                    Clause(IntArray(3) { Lit.make(row + 2 * (row / 16) + it, mask and (1 shl it) == 0) })
                }
            }
            val contradiction = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            )
            val problem = Problem(144, 0, emptyArray(), (parity + contradiction).toTypedArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(50_000),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.UNSAT, completed.verdict)
            val learning = completed.stats.search.learning
            val consumers = if (enabled) 1L else 0L
            assertEquals(consumers, learning["privateRebuildParityJointFactors"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildAutomaticAttempts"] ?: 0L)
            assertEquals(if (enabled) 4_194_304L else 0L,
                learning["privateRebuildAutomaticByteLimit"] ?: 0L)
            assertEquals(if (enabled) 1_048_576L else 0L,
                learning["privateRebuildAutomaticCanonicalScratchLimit"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildConsumers"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildRetired"] ?: 0L)
            assertEquals(listOf("ls/probsat/plain", "bt/clausal-balanced"),
                completed.stats.portfolio.arms.map { it.label })
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `automatic private rebuilding should reach the source portfolio`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")),
                ExecutionOption.privateRebuildWork(-1L), ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "private-rebuild-work=-1",
                "learned-memory-scratch-bytes=4194304")),
        )) {
            val clauses = (0 until 128).flatMap { row ->
                listOf(1, 2, 4, 7).map { mask ->
                    Clause(IntArray(3) { Lit.make(row + 2 * (row / 16) + it, mask and (1 shl it) == 0) })
                }
            } + Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))
            val problem = Problem(144, 0, emptyArray(), clauses.toTypedArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(5_000),
            )
            val samples = mutableListOf<Sample>()

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({ samples += it }, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            assertTrue(samples.isNotEmpty())
            for (sample in samples) {
                assertEquals(problem.numBoolVars, sample.numBoolVars)
                assertTrue(clauses.all { clause ->
                    clause.literals.any { Lit.evaluate(it, sample.boolValue(Lit.variable(it))) }
                })
            }
            val learning = completed.stats.search.learning
            assertTrue(learning.getValue("privateRebuildAutomaticAdmitted") > 0L)
            assertTrue(learning.getValue("privateRebuildConsumers") > 0L)
            assertTrue(learning.getValue("privateRebuildSourceAccepted") > 0L)
            assertEquals(learning.getValue("privateRebuildConsumers"), learning["privateRebuildRetired"])
            assertTrue(completed.stats.portfolio.booleanProof.isEmpty())
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `explicit source proof tasks should decline private rebuild recipes`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")),
                ExecutionOption.privateRebuildWork(65_536L), ExecutionOption.booleanProofTasks(true)),
            ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "private-rebuild-work=65536",
                "boolean-proof-tasks=true")),
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
            val observation = completed.stats.portfolio.booleanProof.single()
            assertEquals("worker-model-or-recipe-mismatch", observation.decline)
            assertEquals(0, observation.tasks)
            assertEquals(0, observation.probeSegments)
        }
    }

    @Test
    fun `unsupported source models should keep ordinary solving and report only explicit task declines`() {
        for (explicit in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail") +
                    if (explicit) listOf("boolean-proof-tasks=true") else emptyList()),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            val observations = completed.stats.portfolio.booleanProof

            assertEquals(if (explicit) 1 else 0, observations.size)
            if (explicit) {
                assertEquals("model-ineligible", observations.single().decline)
                assertEquals(0, observations.single().tasks)
                assertEquals(0, observations.single().probeSegments)
            }
        }
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
    fun `automatic Boolean tasks should bypass optimization and parallel execution`() {
        for ((optimize, cores) in listOf(true to 1, false to 2)) {
            val problem = Problem(2, 0, emptyArray(), emptyArray())
            val objective = LinearObjective(boolWeights = longArrayOf(1L, 0L)).takeIf { optimize }
            val shape = FiniteSolveShape(problem, optimize, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE, cores = cores,
                executionOptions = ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail")),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)

            assertTrue(completed.stats.portfolio.booleanProof.isEmpty())
        }
    }

    @Test
    fun `initial probe policies should require a containing time deadline`() {
        for (policy in listOf("leader-half", "lp-transfer", "heuristic-second", "family-work", "heuristic-work",
            "alns-work")) {
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
    fun `typed initial probe policies should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioProbes("leader-half")),
            ExecutionOptions(ExecutionOption.portfolioProbes("lp-transfer")),
            ExecutionOptions(ExecutionOption.portfolioProbes("heuristic-second")),
            ExecutionOptions(ExecutionOption.portfolioProbes("family-work")),
            ExecutionOptions(ExecutionOption.portfolioProbes("heuristic-work")),
            ExecutionOptions(ExecutionOption.portfolioProbes("alns-work")))) {
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
    fun `parsed initial probe policies should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions.parse(listOf("portfolio-probes=leader-half")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second")),
            ExecutionOptions.parse(listOf("portfolio-probes=family-work")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-work")),
            ExecutionOptions.parse(listOf("portfolio-probes=alns-work")))) {
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
    fun `initial probe policies should compose with deferred LP starts`() {
        for (options in listOf(
            ExecutionOptions.parse(listOf("portfolio-probes=leader-half", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=lp-transfer", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=family-work", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=heuristic-work", "portfolio-lp-start=after-probes")),
            ExecutionOptions.parse(listOf("portfolio-probes=alns-work", "portfolio-lp-start=after-probes")))) {
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
    fun `integer probe policies should reject Boolean sources`() {
        for (policy in listOf("leader-half", "lp-transfer", "family-work", "heuristic-work",
            "alns-work")) {
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
    fun `early heuristic probes should execute Boolean and mixed finite optimization`() {
        for (intCount in listOf(0, 1)) {
            val problem = Problem(1, intCount, Array(intCount) { IntDomain(0, 1) }, emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(boolWeights = longArrayOf(1), intCoefficients = LongArray(intCount) { 1L }),
                null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-probes=heuristic-second")),
                cancellation = Cancellation.after(1.seconds), nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertEquals(FiniteSolveVerdict.OPTIMAL, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        }
    }

    @Test
    fun `early heuristic probes should retain the source annotation worker`() {
        val labels = listOf("ordinary", "heuristic-second").map { policy ->
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val hints = SearchHints(listOf(SearchGroup(intArrayOf(0), intArrayOf(0))))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(longArrayOf(1), longArrayOf(1)), null, null, searchHints = hints)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-probes=$policy")),
                cancellation = Cancellation.after(1.seconds), nodeBudget = NodeBudget(8),
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
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
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
        val configurations = LpCertificationMode.entries.flatMap { mode ->
            listOf(ExecutionOptions(ExecutionOption.portfolioProofShare(0.75)),
                ExecutionOptions.parse(listOf("portfolio-proof-share=0.75"))).map { mode to it }
        }
        for ((mode, options) in configurations) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val shape = FiniteSolveShape(problem, true, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF, lpMode = mode).finiteRequest(
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
