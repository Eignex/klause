package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.withRealDifferenceSystem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FiniteNumericalCompanionsTest {
    @Test
    fun `numerical admission should retain explicit finite recipes and pure integer coverage`() {
        val binary = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val integers = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()).bake()
        val general = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val ordinary = PortfolioScenario.sequential(Kind.COP)
        val cases = listOf(
            Triple(binary, ordinary, true),
            Triple(integers, ordinary, false),
            Triple(general, ordinary, false),
            Triple(binary, ordinary.copy(arms = 1), false),
            Triple(binary, ordinary.copy(explicitArms = true), false),
            Triple(binary, ordinary.copy(arms = PortfolioScenario.DEFAULT_ARMS + 1), false),
            Triple(binary, ordinary.copy(cores = 2), false),
            Triple(binary, ordinary.copy(kind = Kind.CSP), false),
            Triple(binary, ordinary.copy(engine = EngineMix.BACKTRACK), false),
            Triple(binary, ordinary.copy(btEdit = { it }), false),
        )

        for ((model, scenario, admitted) in cases) {
            assertEquals(admitted, finiteNumericalCompanionEligibility(model, scenario,
                LinearObjective(intCoefficients = longArrayOf(1)), TheoryParams()).admitted)
        }
    }

    @Test
    fun `general integer companion admission should explain its fragment and containing costs`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val params = TheoryParams(sourceLpWorkLimit = 100, sourceLpAllocationLimit = 200)

        val ordinary = finiteNumericalCompanionEligibility(model, scenario, objective, params)
        val trial = finiteNumericalCompanionEligibility(model, scenario.copy(companionIntegers = true),
            objective, params)

        assertEquals(listOf("binary-integers-required"), ordinary.declines)
        assertEquals(emptyList(), trial.declines)
        assertEquals(2L, trial.requirements.construction.columns)
        assertEquals(50L, trial.requirements.construction.lpWorkLimit)
        assertEquals(100L, trial.requirements.construction.allocationLimit)
        assertEquals(4_000L, trial.requirements.construction.activeMillis)
    }

    @Test
    fun `integrated companions should report distinct proof authority and preserve finite workers`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = model.prepareExecutionPlan(objective, preferFinite = true)
        val events = mutableListOf<SearchEvent.WorkerAdmission>()
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(companionIntegers = true)
        val executor = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null,
            { _, event -> if (event is SearchEvent.WorkerAdmission) events += event },
            null, plan, Cancellation.Never, numericalCompletion = true, numericalCompanions = TheoryParams())

        executor.use { result ->
            val completed = result.minimize(Cancellation.Never, null)
            assertEquals(0.0, assertIs<MinimizeResult.Optimal>(completed).objective)
        }

        val frontier = events.single { it.recipe == "numerical/finite-companion" }
        val repair = events.single { it.recipe == "ls/finite-joint-companion" }
        assertEquals(true, frontier.admitted)
        assertEquals(true, repair.admitted)
        assertTrue(frontier.requirements.contains("authority=CHECKED_TERMINALS"))
        assertTrue(repair.requirements.contains("authority=WITNESS_ONLY"))
        assertTrue(events.any { it.recipe == "satOptimized" && it.admitted })
    }

    @Test
    fun `a checked numerical optimum should complete the same finite working model`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val context = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val result = OpenTheoryPipeline.optimizeLinear(context)

        val completed = finiteNumericalCompletion(result, context, Cancellation.Never)

        assertEquals(0.0, assertIs<MinimizeResult.Optimal>(completed).objective)
    }

    @Test
    fun `a checked numerical refutation should complete the same finite working model`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val context = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val result = OpenTheoryPipeline.optimizeLinear(context)

        val completed = finiteNumericalCompletion(result, context, Cancellation.Never)

        assertIs<MinimizeResult.Infeasible>(completed)
    }

    @Test
    fun `admitting numerical workers should append to the finite composition`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = model.prepareExecutionPlan(objective, preferFinite = true)
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val baseline = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
            plan, Cancellation.Never, numericalCompletion = true)
        val candidate = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
            plan, Cancellation.Never, numericalCompletion = true, numericalCompanions = TheoryParams())

        baseline.use { before ->
            candidate.use { after -> assertEquals(before.resources.slots.size + 2, after.resources.slots.size) }
        }
    }

    @Test
    fun `a restricted numerical proof should not finish its weaker finite invocation`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val context = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val restricted = SourceProofContext(context.scope.copy(condition = SourceCondition(
            lower = mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.ONE))),
            context.coordinates, context.premises, context.objective, context.objectiveConstant)
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        val completed = finiteNumericalCompletion(result, context, Cancellation.Never)

        assertIs<MinimizeResult.BestFound>(completed)
    }
}
