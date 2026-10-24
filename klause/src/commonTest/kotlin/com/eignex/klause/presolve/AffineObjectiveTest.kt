package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffineObjectiveTest {
    private val source = Problem(
        0, 3, Array(3) { IntDomain(0, 4) },
        listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 1),
        ),
    )
    private val objective = LinearObjective(intCoefficients = longArrayOf(2, -2, 3), constant = 5)

    @Test
    fun `source and finite preparation should compose translated objectives in original units`() {
        val prepared = PresolvePipeline.prepareSource(source, PresolveConfig.parse("affine"), objective,
            finiteLane = true)
        val outcome = PresolvePipeline.run(prepared, objective, PresolveConfig.parse("affine"), false)
        val transformed = assertNotNull(outcome.objective)

        assertEquals(2, outcome.mapping.rebuild.steps.filterIsInstance<RebuildStep.AffineValue>().size)
        val assignments = BacktrackSolver(outcome.problem.bake()).enumerate().map { reduced ->
            outcome.mapping.requireObjectivePreserved(objective, transformed, reduced)
            outcome.reconstruct(reduced).ints.toList()
        }.toSet()
        assertEquals(setOf(listOf(2L, 1L, 0L), listOf(3L, 2L, 1L), listOf(4L, 3L, 2L)), assignments)
        assertEquals(listOf(2L, -2L, 3L), objective.intCoefficients.toList())
        assertEquals(5L, objective.constant)
    }

    @Test
    fun `finite preparation should hand transformed coefficients to minimization and maximization`() {
        for (maximize in listOf(false, true)) {
            val normalized = if (maximize) objective.negated() else objective
            val preparation = FinitePipeline.prepare(FinitePipelineRequest(
                source, FiniteEngine.BACKTRACK, normalized, PresolveConfig.parse("affine"),
            ))
            val result = BacktrackSolver(preparation.problem.bake()).minimize(assertNotNull(preparation.objective))
            val reduced = assertIs<MinimizeResult.Optimal>(result).sample
            val original = preparation.reconstruct(reduced)

            assertEquals(if (maximize) 13L else 7L, objective.evaluateLong(original))
            assertEquals(normalized.evaluateLong(original), preparation.objective?.evaluateLong(reduced))
        }
    }

    @Test
    fun `source execution should publish reconstructed optima in the original direction`() {
        for (maximize in listOf(false, true)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(source), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
                objective, maximize,
            ))

            val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(SourceExecutionRequest(
                invocation, presolveConfig = PresolveConfig.parse("affine"),
            )))

            val optimum = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
            val sample = assertNotNull(optimum.bestSample)
            assertEquals(FiniteSolveVerdict.OPTIMAL, optimum.verdict)
            assertEquals(if (maximize) 13L else 7L, objective.evaluateLong(sample))
            assertEquals(sample.intValue(1) + 1L, sample.intValue(0))
            assertEquals(sample.intValue(2) + 1L, sample.intValue(1))
            val plan = assertNotNull(result.result.preparation.executionPlan)
            assertEquals(objective, plan.objective)
            assertEquals(maximize, plan.maximize)
        }
    }

    @Test
    fun `supplemental objective consumers should keep their original coordinates`() {
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(
            source, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse("affine"),
            transformObjective = false,
        ))

        assertEquals(objective, preparation.objective)
        assertTrue(assertNotNull(preparation.sourceMapping).rebuild.isEmpty)
    }

    @Test
    fun `translation should carry finite domain holes into the retained coordinate`() {
        val model = Problem(0, 2, arrayOf(
            IntDomain(1, 4).excludeValues(longArrayOf(2))!!,
            IntDomain(0, 3).excludeValues(longArrayOf(2))!!,
        ), listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1))).bake()
        val cost = LinearObjective(intCoefficients = longArrayOf(2, 3))
        val outcome = PresolvePipeline.run(model, cost, PresolveConfig.parse("affine"), false)

        val assignments = BacktrackSolver(outcome.problem.bake()).enumerate().map { reduced ->
            outcome.mapping.requireObjectivePreserved(cost, assertNotNull(outcome.objective), reduced)
            outcome.reconstruct(reduced).ints.toList()
        }.toSet()

        assertTrue(outcome.mapping.rebuild.steps.any { it is RebuildStep.AffineValue })
        assertEquals(setOf(listOf(1L, 0L), listOf(4L, 3L)), assignments)
    }

    @Test
    fun `coefficient overflow should leave both objective columns protected`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val cost = LinearObjective(intCoefficients = longArrayOf(Long.MAX_VALUE, 1))

        val outcome = PresolvePipeline.run(model, cost, PresolveConfig.parse("affine"), false)

        assertTrue(outcome.mapping.rebuild.isEmpty)
        assertEquals(cost, outcome.objective ?: cost)
    }

    @Test
    fun `constant overflow should retain the defining equality`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)))
        val cost = LinearObjective(intCoefficients = longArrayOf(1, -1), constant = Long.MAX_VALUE)

        val outcome = PresolvePipeline.run(model, cost, PresolveConfig.parse("affine"), false)

        assertTrue(outcome.mapping.rebuild.isEmpty)
        assertEquals(cost, outcome.objective ?: cost)
    }

    @Test
    fun `translation should extend a partial objective to its retained partner`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)))
        val cost = LinearObjective(intCoefficients = longArrayOf(1))

        val outcome = PresolvePipeline.run(model, cost, PresolveConfig.parse("affine"), false)
        val values = BacktrackSolver(outcome.problem.bake()).enumerate().map { reduced ->
            outcome.mapping.requireObjectivePreserved(cost, assertNotNull(outcome.objective), reduced)
            cost.evaluateLong(outcome.reconstruct(reduced))
        }.toSet()

        assertEquals(setOf(1L, 2L), values)
        assertTrue(outcome.mapping.rebuild.steps.any { it is RebuildStep.AffineValue })
    }

    @Test
    fun `wider affine expressions should retain objective column protection`() {
        val model = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)))

        val outcome = PresolvePipeline.run(model, objective, PresolveConfig.parse("affine"), false)

        assertTrue(outcome.mapping.rebuild.isEmpty)
    }

    @Test
    fun `approximate source semantics should retain objective column protection`() {
        val semantics = SourceSemantics(SourceFormat.IR,
            feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ofLong(1))).resolve()
        val model = source.withSourceSemantics(semantics)

        val outcome = PresolvePipeline.run(model, objective, PresolveConfig.parse("affine"), false)

        assertTrue(outcome.mapping.rebuild.isEmpty)
    }

    @Test
    fun `protection only contexts should leave the objective coordinates in place`() {
        val result = Presolver.runSource(source, PresolveConfig.parse("affine"), PresolveContext.of(objective))

        assertTrue(result.rebuild.isEmpty)
        assertNull(result.objective)
    }

    @Test
    fun `cancellation should retain objective equality for every committed translation`() {
        for (limit in listOf(0, 5, 20, 100)) {
            var polls = 0
            val outcome = PresolvePipeline.run(source, objective, PresolveConfig.parse("affine"), false,
                Cancellation { polls++ >= limit })

            val values = BacktrackSolver(outcome.problem.bake()).enumerate().map { reduced ->
                outcome.mapping.requireObjectivePreserved(objective, outcome.objective ?: objective, reduced)
                objective.evaluateLong(outcome.reconstruct(reduced))
            }.toSet()
            assertEquals(setOf(7L, 10L, 13L), values)
        }
    }
}
