package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioProgress
import com.eignex.klause.portfolio.PortfolioRewardPolicy
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
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FiniteNumericalCompanionsTest {
    @Test
    fun `funded companions should avoid capture when cancellation precedes every query`() {
        for (unavailable in listOf(false, true)) {
            val factors = if (unavailable) arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0))
            else emptyArray()
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), factors, numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val plan = model.prepareExecutionPlan(objective, preferFinite = true)
            val scenario = PortfolioScenario.sequential(Kind.COP).copy(companionAfterIncumbent = true)
            val clock = TestTimeSource()

            val result = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
                plan, Cancellation.until(clock.markNow() + 30.seconds), numericalCompletion = true,
                numericalCompanions = TheoryParams(), fundedCompanions = true).use {
                it.minimize(Cancellation { true }, null)
            }

            val stages = result.stats.smt.completeSearchStages.orEmpty()
            assertEquals(0L, stages["finiteCompanionPreparationAttempts"])
            assertEquals(0L, stages["finiteCompanionPreparationNs"])
            assertEquals(0L, stages["finiteCompanionPreparationReady"])
            assertEquals(0L, stages["finiteCompanionPreparationUnavailable"])
            assertTrue(result.stats.portfolio.arms.all { it.lifecycle.firstQueryNanos == null })
        }
    }

    @Test
    fun `unfunded companion admission should preserve the finite worker slots and seeds`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = model.prepareExecutionPlan(objective, preferFinite = true)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(companionAfterIncumbent = true)
        val events = mutableListOf<SearchEvent.WorkerAdmission>()
        val clock = TestTimeSource()
        val lifetime = Cancellation.until(clock.markNow() + 5.seconds)
        val baseline = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
            plan, lifetime, numericalCompletion = true)
        val candidate = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null,
            { _, event -> if (event is SearchEvent.WorkerAdmission) events += event }, null,
            plan, lifetime, numericalCompletion = true, numericalCompanions = TheoryParams(),
            fundedCompanions = true)

        baseline.use { before -> candidate.use { after ->
            assertEquals(before.resources.slots.map { it.seed }, after.resources.slots.map { it.seed })
        } }

        val declined = events.filter { it.recipe.endsWith("finite-companion") || it.recipe.endsWith("joint-companion") }
        assertEquals(2, declined.size)
        assertTrue(declined.all {
            !it.admitted && !it.selected && it.declines == listOf("companion-lifetime-unfunded")
        })
    }

    @Test
    fun `funded admission should preserve an explicit finite selection`() {
        for (explicit in listOf(PortfolioScenario.sequential(Kind.COP).copy(explicitArms = true),
            PortfolioScenario.sequential(Kind.COP).copy(arms = 1))) {
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val plan = model.prepareExecutionPlan(objective, preferFinite = true)
            val scenario = explicit.copy(companionAfterIncumbent = true)
            val clock = TestTimeSource()
            val lifetime = Cancellation.until(clock.markNow() + 30.seconds)
            val baseline = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
                plan, lifetime, numericalCompletion = true)
            val candidate = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
                plan, lifetime, numericalCompletion = true, numericalCompanions = TheoryParams(),
                fundedCompanions = true)

            baseline.use { before -> candidate.use { after ->
                assertEquals(before.resources.slots.map { it.seed }, after.resources.slots.map { it.seed })
            } }
        }
    }

    @Test
    fun `preparation should time ready and cancelled affine captures`() {
        for (ready in listOf(false, true)) {
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val cost = finiteNumericalCompanionEligibility(model, PortfolioScenario.sequential(Kind.COP),
                objective, TheoryParams()).requirements.construction
            val clock = TestTimeSource()
            val cancellation = Cancellation {
                clock += 1.microseconds
                !ready
            }

            val prepared = FiniteNumericalCompanions.prepare(model, objective, TheoryParams(), cancellation, cost,
                clock = clock)

            assertEquals(ready, prepared.companions != null)
            assertTrue(prepared.elapsedNanos >= 1_000)
        }
    }

    @Test
    fun `preparation accounting should survive cancellation before any worker query`() {
        for (unavailable in listOf(false, true)) {
            val factors = if (unavailable) arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0))
            else emptyArray()
            val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), factors, numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val plan = model.prepareExecutionPlan(objective, preferFinite = true)
            val scenario = PortfolioScenario.sequential(Kind.COP).copy(companionAfterIncumbent = true)

            val result = FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
                plan, Cancellation.Never, numericalCompletion = true, numericalCompanions = TheoryParams()).use {
                it.minimize(Cancellation { true }, null)
            }

            val stages = result.stats.smt.completeSearchStages.orEmpty()
            assertEquals(1L, stages["finiteCompanionPreparationAttempts"])
            assertEquals(if (unavailable) 0L else 1L, stages["finiteCompanionPreparationReady"])
            assertEquals(if (unavailable) 1L else 0L, stages["finiteCompanionPreparationUnavailable"])
            assertTrue(requireNotNull(stages["finiteCompanionPreparationNs"]) >= 0L)
            assertTrue(result.stats.portfolio.arms.all { it.lifecycle.firstQueryNanos == null })
        }
    }

    @Test
    fun `a finite source invocation should deliver accepted progress to the reward interface`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = model.prepareExecutionPlan(objective, preferFinite = true)
        val observed = mutableListOf<PortfolioProgress>()
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(rewardPolicy = PortfolioRewardPolicy {
            observed += it
            it.ordinaryReward
        })

        FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null, null,
            plan, Cancellation.Never, numericalCompletion = true, numericalCompanions = TheoryParams()).use {
            it.minimize(Cancellation.Never, null)
        }

        assertTrue(observed.any { it.acceptedIncumbents > 0 })
    }

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
    fun `companion lifetime should reach admission costs without changing its fragment`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            companionAfterIncumbent = true, companionActiveMillis = 7_000)

        val eligibility = finiteNumericalCompanionEligibility(model, scenario, objective, TheoryParams())

        assertEquals(listOf("binary-integers-required"), eligibility.declines)
        assertEquals(7_000L, eligibility.requirements.construction.activeMillis)
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
        assertEquals(true, frontier.selected)
        assertEquals(true, repair.admitted)
        assertEquals(true, repair.selected)
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
