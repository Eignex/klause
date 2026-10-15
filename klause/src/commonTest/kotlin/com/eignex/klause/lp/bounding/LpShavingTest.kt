package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.BacktrackWorkerConfig
import com.eignex.klause.portfolio.SharedObjectiveBound
import com.eignex.klause.portfolio.SharedPools
import com.eignex.klause.portfolio.SharedVarBounds
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpShavingTest {
    @Test
    fun `the redundancy probe should reach a verdict on a model with continuous columns`() {
        // The probe rebuilds the model over the kept rows; dropping the real columns would orphan a real row.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4),
                ReifiedRealLinear(
                    aux = 0,
                    vars = IntArray(0),
                    intCoeffs = DoubleArray(0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val engine = LpEngine(
            p,
            LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "shave"),
        )
        assertEquals(listOf(0), engine.redundantConstraints(Cancellation.Never))
    }

    @Test
    fun `unbound shaving worker should keep its exhausted incumbent without sharing a floor`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val bounds = SharedObjectiveBound(identity = model)
        val pools = SharedPools(null, null, bounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(
            BacktrackRecipe("shaving") { _, _ ->
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true))
            },
        )
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)

        val result = worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        val completed = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(2.0, completed.objective)
        assertEquals(TerminationReason.SearchExhausted, completed.reason)
        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
    }

    @Test
    fun `source bound shaving worker should publish the floor to the shared bound`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val bounds = SharedObjectiveBound(identity = model)
        val pools = SharedPools(null, null, bounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(
            BacktrackRecipe("shaving") { _, _ ->
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true))
            },
        )
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)
            .let {
                it.bindSource(
                    SourceWorkerBinding(
                        invocation,
                        sharingInvocation = invocation,
                        provesResults = it.permitsSourceProofs,
                    ),
                )
            }

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertEquals(2.0, bounds.current())
    }

    private fun triangleCover(): Problem = Problem(
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
    fun `objective shaving should preserve the optimum`() {
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
        assertEquals(2.0, on.objectiveValue)
    }

    @Test
    fun `objective shaving should publish the proven floor to the lower bound sink`() {
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
        assertTrue(result is MinimizeResult.Optimal && result.objectiveValue == 2.0)
        assertTrue(published.any { it >= 2.0 }, "published: $published")
    }

    @Test
    fun `unbound shaving worker should keep its exhausted incumbent without sharing bounds`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val bounds = SharedVarBounds(4, identity = model.forObjective(null))
        val pools = SharedPools(null, null, varBounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(
            BacktrackRecipe("shaving") { _, _ ->
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, variableShaving = true))
            },
        )
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)

        val result = worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        val completed = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(2.0, completed.objective)
        assertEquals(TerminationReason.SearchExhausted, completed.reason)
        assertEquals(Long.MIN_VALUE, bounds.lowerOf(3))
    }

    @Test
    fun `source bound shaving worker should publish to the shared bound pool`() {
        val source = triangleCover()
        val executable = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val model = ModelIdentity.of(executable, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val bounds = SharedVarBounds(4, identity = model.forObjective(null))
        val pools = SharedPools(null, null, varBounds = bounds, identity = model)
        val config = BacktrackWorkerConfig(
            BacktrackRecipe("shaving") { _, _ ->
                BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, variableShaving = true))
            },
        )
        val worker = config.materialize(executable, 0, 0, 1L, 1.0, objective, null, null, null, pools)
            .let {
                it.bindSource(
                    SourceWorkerBinding(
                        invocation,
                        sharingInvocation = invocation,
                        provesResults = it.permitsSourceProofs,
                    ),
                )
            }

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertEquals(2L, bounds.lowerOf(3))
    }

    private fun problem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 9)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
    )

    @Test
    fun `variable shaving should publish a beyond-bake bound to the sink`() {
        val published = HashMap<Int, Pair<Long, Long>>()
        val res = BacktrackSolver(triangleCover().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)),
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, variableShaving = true),
                globalVarBoundSink = { v, lo, hi -> published[v] = lo to hi },
            ),
        )
        assertTrue(res is MinimizeResult.Optimal && res.objectiveValue == 2.0)
        assertEquals(2, published[3]?.first, "published: $published")
    }

    @Test
    fun `importing a shared bound should preserve the optimum`() {
        val res = BacktrackSolver(problem().bake()).minimize(
            LinearObjective(intCoefficients = longArrayOf(1)),
            BacktrackParams(
                randomSeed = 1L,
                globalVarLowerSupplier = { v -> if (v == 0) 3L else Int.MIN_VALUE.toLong() },
                globalVarUpperSupplier = { Int.MAX_VALUE.toLong() },
            ),
        )
        assertTrue(res is MinimizeResult.Optimal && res.objectiveValue == 3.0)
    }
}
