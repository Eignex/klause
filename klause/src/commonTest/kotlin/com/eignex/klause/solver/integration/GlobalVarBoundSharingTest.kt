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
import com.eignex.klause.portfolio.SharedPools
import com.eignex.klause.portfolio.SharedVarBounds
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalVarBoundSharingTest {

    @Test
    fun `unbound variable shaving retains its native optimum without sharing a window`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val bounds = SharedVarBounds(4, identity = model.forObjective(null))
        val pools = SharedPools(null, null, varBounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(BacktrackRecipe("shaving") { _, _ ->
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, variableShaving = true))
        })
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)

        val result = worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertTrue(result is MinimizeResult.Optimal && result.objectiveValue == 2.0)
        assertEquals(Long.MIN_VALUE, bounds.lowerOf(3))
    }

    @Test
    fun `actual native variable shaving reaches a runtime bound pool`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val bounds = SharedVarBounds(4, identity = model.forObjective(null))
        val pools = SharedPools(null, null, varBounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(BacktrackRecipe("shaving") { _, _ ->
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, variableShaving = true))
        })
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)
            .bindInvocation(invocation).bindSharingInvocation(invocation)

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertEquals(2L, bounds.lowerOf(3))
    }

    private fun problem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 9)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
    )

    /** Triangle vertex cover: `cost = x0+x1+x2` over {0,1}³ with the three pair-covering rows. Every
     *  solution needs ≥ 2 ones, so `cost ≥ 2` — a bound bake propagation cannot derive but variable
     *  shaving (LP + propagation) proves, so it is a beyond-bake tightening worth sharing. */
    private fun triangleCover() = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `a beyond-bake shaving tightening reaches the sink`() {
        val published = HashMap<Int, Pair<Long, Long>>()
        val res = BacktrackSolver(triangleCover().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)), // minimize cost (var 3)
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, variableShaving = true),
                globalVarBoundSink = { v, lo, hi -> published[v] = lo to hi },
            ),
        )
        assertTrue(res is MinimizeResult.Optimal && res.objectiveValue == 2.0)
        assertEquals(2, published[3]?.first, "shaving proves cost >= 2, which must be published, got $published")
    }

    @Test
    fun `importing a shared bound preserves the optimum`() {
        val res = BacktrackSolver(problem().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(1)),
            BacktrackParams(
                randomSeed = 1L,
                globalVarLowerSupplier = { v -> if (v == 0) 3L else Int.MIN_VALUE.toLong() },
                globalVarUpperSupplier = { Int.MAX_VALUE.toLong() },
            ),
        )
        assertTrue(
            res is MinimizeResult.Optimal && res.objectiveValue == 3.0,
            "importing a valid bound keeps the optimum",
        )
    }
}
