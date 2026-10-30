package com.eignex.klause.portfolio

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.heuristicHints
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(Parameterized::class)
class LocalSearchWorkerBooleanCostsTest(
    private val minimize: Boolean,
    private val guidance: ObjectiveGuidance,
    private val perMove: Boolean,
    private val existingSweep: Boolean,
    private val wcnf: Boolean,
) {
    @Test
    fun `selected Boolean cost guidance should preserve the original point and objective`() {
        val source = Dimacs.parseWcnf("7 1 2 0\n11 -1 2 0\n").toProblem()
        val attached = if (wcnf) source.problem else Problem(source.problem.numBoolVars, 0,
            emptyArray(), source.problem.factors)
        val canonical = source.objective.toLinearObjective().let { if (minimize) it else it.negated() }
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(attached, intArrayOf(), source.boolFolds),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, canonical,
        ))
        val shape = invocation.finiteShape()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(shape.problem, FiniteEngine.LOCAL_SEARCH,
            shape.linearObjective, PresolveConfig.NONE, preparedPlan = shape.preparedPlan))
        val problem = assertIs<BakedProblem>(preparation.problem)
        val hints = assertNotNull(invocation.heuristicHints(problem, preparation.objective, preparation.sourceMapping,
            Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))
        assertNull(hints.sweep)
        val gradient = assertNotNull(hints.objective)
        val supplied = if (existingSweep) {
            assertNotNull(DefinitionalSweep.infer(problem, boolFolds = source.boolFolds.take(1)))
        } else null
        val recipe = LocalSearchRecipe("boolean-cost", Cbls(), perMoveInvariants = perMove,
            objectiveGuidance = guidance)
        val worker = LocalSearchWorkerConfig(recipe, reportPhaseTimes = true).materialize(
            problem, 0, 0, 3L, 1.0, canonical, gradient, supplied, null, null,
        )

        val result = worker.use {
            worker.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never, maxInstructions = 16L).last()
        }

        val point = assertIs<MinimizeResult.WithSample>(result).sample
        val first = point.boolValue(0)
        val second = point.boolValue(1)
        val cost = if (second) 0L else if (first) 11L else 7L
        val expected = if (minimize) cost else -cost
        assertEquals(expected, canonical.evaluateLong(point))
        assertEquals(expected.toDouble(), result.objectiveValue)
        val functional = guidance == ObjectiveGuidance.Functional && perMove && (existingSweep || wcnf)
        val observations = result.stats.ls.objectiveScoring
        assertEquals(if (functional) 1L else 0L, observations.getValue("functional"))
        assertEquals(if (functional && !existingSweep) 1L else 0L, observations.getValue("maintained"))
        assertEquals(if (functional && existingSweep) 1L else 0L, observations.getValue("prepared"))
        if (functional && !existingSweep) {
            assertEquals(if (minimize) 0L else -11L, expected)
            assertEquals(0L, observations.getValue("indexed"))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "minimize={0}, guidance={1}, perMove={2}, existing={3}, wcnf={4}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { minimize ->
            listOf(ObjectiveGuidance.Functional to true, ObjectiveGuidance.Functional to false,
                ObjectiveGuidance.Canonical to true, ObjectiveGuidance.Geometric to true).flatMap {
                (guidance, perMove) ->
                listOf(false, true).flatMap { existing ->
                    listOf(false, true).map { wcnf -> arrayOf<Any>(minimize, guidance, perMove, existing, wcnf) }
                }
            }
        }
    }
}
