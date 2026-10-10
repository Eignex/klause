package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {

    @Test
    fun `curated satisfaction root cuts require an explicit override`() {
        for (requested in listOf(null, false, true)) {
            val edit = requested?.let { enabled ->
                { params: BacktrackParams -> params.copy(lpPlan = params.lpPlan.copy(rootCutHarvest = enabled)) }
            }
            val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 4)
                .copy(btEdit = edit)

            val params = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }.filter { it.lpConfig != null }

            assertEquals(2, params.size)
            assertEquals(List(2) { requested == true }, params.map { it.lpPlan.rootCutHarvest })
        }
    }

    @Test
    fun `optimization and explicit satisfaction pools retain their root cut plans`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 4),
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 1)
                .copy(btPool = listOf { BacktrackCatalog.byLabel("lp-aggressive") }),
        )

        for (scenario in scenarios) {
            val params = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }.filter { it.lpConfig != null }

            assertTrue(params.isNotEmpty())
            assertTrue(params.all { it.lpPlan.rootCutHarvest })
        }
    }

    @Test
    fun `expanding a sequential optimization pool keeps the small pool positions`() {
        val small = PortfolioScenario.sequential(Kind.COP)
        for (problemClass in listOf(ProblemClass.FiniteCp, ProblemClass.MixedInteger)) {
            val facts = ProblemFacts.assumed(Kind.COP, problemClass)

            val baseline = PortfolioComposition.plan(small, facts)
            val expanded = PortfolioComposition.plan(small.copy(arms = 12), facts)

            assertEquals(baseline.arms.map { it.label }, expanded.arms.take(baseline.arms.size).map { it.label })
            assertEquals(baseline.firstSolutionCount, expanded.firstSolutionCount)
            assertEquals(13, expanded.arms.size)
        }
    }

    @Test
    fun `auxiliary LP joins finite objectives after the incumbent and continuous objectives before it`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP, ProblemClass.MixedInteger).profile

        for (continuousObjective in listOf(false, true)) {
            val facts = ProblemFacts(profile, continuousObjective = continuousObjective) { true }

            val plan = PortfolioComposition.plan(scenario, facts)

            assertEquals("lp-default", plan.arms.last().label)
            assertEquals(plan.arms.size - if (continuousObjective) 0 else 1, plan.firstSolutionCount)
        }
    }

    @Test
    fun `explicit and parallel pools can use every arm for a first solution`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.CSP, arms = 12),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 12),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.LOCAL_SEARCH, arms = 12),
            PortfolioScenario.parallel(4, Kind.COP, arms = 12),
            PortfolioScenario.sequential(Kind.COP, arms = 12)
                .copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            PortfolioScenario.sequential(Kind.COP, arms = 12)
                .copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
        )
        for (scenario in scenarios) {
            val plan = PortfolioComposition.plan(scenario, ProblemFacts.assumed(scenario.kind))

            assertEquals(plan.arms.size, plan.firstSolutionCount, "$scenario")
        }
    }

    @Test
    fun `mixed optimization schedules a complete arm before local search`() {
        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP))

        assertIs<BacktrackWorkerConfig>(arms.first())
    }

    @Test
    fun `an injected backtrack pool is built as asked whatever the model offers`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 2)
            .copy(btPool = listOf { BacktrackCatalog.byLabel("lp-default") })
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, relaxation = { false })

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertEquals(listOf("lp-default", "lp-default"), labels)
    }

    @Test
    fun `the node allowance reaches every arm that runs a backtrack engine`() {
        val budget = NodeBudget(limit = 100)
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.MIXED, arms = 6).copy(nodeBudget = budget),
        )

        for (arm in arms) {
            when (arm) {
                is BacktrackWorkerConfig ->
                    assertSame(budget, arm.recipe.build(1L, null).nodeBudget, "backtrack arm ${arm.label}")

                is AlnsWorkerConfig -> assertSame(budget, arm.nodeBudget, "hybrid-ALNS arm ${arm.label}")

                else -> Unit
            }
        }
        assertTrue(arms.any { it is AlnsWorkerConfig }, "a mixed COP pool carries a hybrid-ALNS arm to check")
    }

    @Test
    fun `the ALNS engine's arms spend the node allowance`() {
        val budget = NodeBudget(limit = 100)
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.ALNS, arms = 3).copy(nodeBudget = budget),
        )

        assertEquals(listOf(budget, budget, budget), arms.map { (it as AlnsWorkerConfig).nodeBudget })
    }

    @Test
    fun `the model's annotation arm spends the node allowance`() {
        val budget = NodeBudget(limit = 100)
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 3)
                .copy(annotationArm = BacktrackParams(), nodeBudget = budget),
        )

        val annotation = arms.filterIsInstance<BacktrackWorkerConfig>().single { it.label == "annotation" }
        assertSame(budget, annotation.recipe.build(1L, null).nodeBudget)
    }
}
