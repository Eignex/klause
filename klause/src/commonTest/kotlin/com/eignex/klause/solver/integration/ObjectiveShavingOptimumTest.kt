package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.portfolio.BacktrackWorkerConfig
import com.eignex.klause.portfolio.SharedObjectiveBound
import com.eignex.klause.portfolio.SharedPools
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObjectiveShavingOptimumTest {

    @Test
    fun `unbound objective shaving retains its exhausted incumbent without sharing a floor`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val bounds = SharedObjectiveBound(identity = model)
        val pools = SharedPools(null, null, bounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(BacktrackRecipe("shaving") { _, _ ->
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true))
        })
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)

        val result = worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        val completed = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(2.0, completed.objective)
        assertEquals(TerminationReason.SearchExhausted, completed.reason)
        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
    }

    @Test
    fun `actual native objective shaving reaches a runtime bound pool`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val bounds = SharedObjectiveBound(identity = model)
        val pools = SharedPools(null, null, bounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(BacktrackRecipe("shaving") { _, _ ->
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true))
        })
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)
            .bindInvocation(invocation).bindSharingInvocation(invocation)

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertEquals(2.0, bounds.current())
    }

    /** `cost = x0+x1+x2` over {0,1}³ with the three pair-covering rows (triangle vertex cover): every
     *  solution needs ≥ 2 ones, so the minimum `cost` is 2 — but its declared domain min is 0. */
    private fun triangleCover(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4, // x0, x1, x2, cost
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0), // cost channelling
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `shaving preserves the optimum end to end`() {
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val off = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(2.0, off.objectiveValue)
        assertEquals(2.0, on.objectiveValue, "objective shaving changed the optimum")
    }

    @Test
    fun `the proven objective floor reaches the lower-bound sink`() {
        // The shaved floor (cost >= 2) is a global lower bound; it must be published to the portfolio's
        // shared lower-bound sink so a peer arm can pick it up.
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val published = ArrayList<Double>()
        val result = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, objectiveShaving = true),
                objectiveLowerBoundSink = { published.add(it) },
            ),
        )
        assertTrue(result is MinimizeResult.Optimal && result.objectiveValue == 2.0, "optimum is 2")
        assertTrue(published.any { it >= 2.0 }, "the proven floor (cost >= 2) must reach the sink, got $published")
    }
}
