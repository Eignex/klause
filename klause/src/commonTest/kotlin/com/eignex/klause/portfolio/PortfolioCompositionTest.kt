package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {

    @Test
    fun `integer linear companion preserves existing worker positions`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = PortfolioComposition.compose(scenario, ProblemFacts(profile, binaryIntegers = true) { true })

        val composed = PortfolioComposition.compose(
            scenario, ProblemFacts(profile, binaryIntegers = true, integerLinear = true) { true },
        )

        assertEquals(baseline.map { it.label } + "lp-aggressive", composed.map { it.label })
    }

    @Test
    fun `integer linear companion remains available with cuts disabled`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, integerLinear = true) { true }
        val baseline = PortfolioComposition.compose(scenario, facts)

        val composed = PortfolioComposition.compose(
            scenario.copy(lpCeiling = LpConfig.parse("aggressive,-cuts")), facts,
        )

        assertEquals(baseline.map { it.label }, composed.map { it.label })
    }

    @Test
    fun `explicit and parallel integer linear pools retain their composition`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = ProblemFacts(profile) { true }
        val integer = ProblemFacts(profile, integerLinear = true) { true }
        for (control in listOf(
            scenario.copy(cores = 2), scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP), scenario.copy(arms = 5), scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )) {
            assertEquals(PortfolioComposition.compose(control, baseline).map { it.label },
                PortfolioComposition.compose(control, integer).map { it.label })
        }
    }

    @Test
    fun `finite mixed optimization admits selector diversity without a relaxation`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val facts = ProblemFacts(profile) { false }

        val plan = PortfolioComposition.plan(scenario, facts)

        assertEquals(listOf("satOptimized", "selector-switch"),
            plan.arms.filterIsInstance<BacktrackWorkerConfig>().map { it.label })
        assertEquals(4, plan.arms.count { it is LocalSearchWorkerConfig })
    }

    @Test
    fun `explicit continuous and relaxation pools retain their leading recipes`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val finite = ProblemFacts(profile) { false }
        val controls = listOf(
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(arms = 5),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )

        for (control in controls) {
            assertTrue(PortfolioComposition.compose(control, finite).filterIsInstance<BacktrackWorkerConfig>()
                .take(2).none { it.label == "selector-switch" })
        }
        for (problemClass in listOf(ProblemClass.MixedInteger, ProblemClass.Sat, ProblemClass.PseudoBoolean)) {
            val facts = ProblemFacts(profile.copy(problemClass = problemClass)) { false }
            assertTrue(PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                .take(2).none { it.label == "selector-switch" })
        }
        val relaxation = ProblemFacts(profile) { true }
        assertEquals(listOf("satOptimized", "lp-default"),
            PortfolioComposition.compose(scenario, relaxation).take(2).map { it.label })
    }

    @Test
    fun `expanded finite pools keep the admitted selector position without duplicating it`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { false }
        val small = PortfolioComposition.plan(scenario, facts)

        val expanded = PortfolioComposition.plan(scenario.copy(arms = 18), facts)

        assertEquals(small.arms.map { it.label }, expanded.arms.take(small.arms.size).map { it.label })
        assertEquals(1, expanded.arms.count { it.label == "selector-switch" })
        assertEquals(1, expanded.arms.count { it.label == "conflictDriven" })
    }

    @Test
    fun `expanded pools retain the initial selector beside later relaxation recipes`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { it == LpEmphasis.CONSERVATIVE }
        val small = PortfolioComposition.plan(scenario, facts)

        val expanded = PortfolioComposition.plan(scenario.copy(arms = 12), facts)

        assertEquals(small.arms.map { it.label }, expanded.arms.take(small.arms.size).map { it.label })
        assertTrue(expanded.arms.any { it.label == "lp-conservative" })
    }

    @Test
    fun `scheduled mixed optimization pairs primary and full plateau neighborhoods`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val ordinary = ProblemFacts.assumed(Kind.COP)
        val scheduling = ProblemFacts(ordinary.profile.copy(scheduling = true)) { true }
        val baseline = PortfolioComposition.compose(scenario, ordinary).filterIsInstance<LocalSearchWorkerConfig>()

        val paired = PortfolioComposition.compose(scenario, scheduling).filterIsInstance<LocalSearchWorkerConfig>()

        assertEquals("cbls-primary-plateau/ils-basin", paired.first().label)
        assertEquals("cbls-plateau/ils-basin", paired[1].label)
        assertEquals(baseline.drop(1).map { it.label }, paired.drop(1).map { it.label })
        assertEquals(baseline.size, paired.size)
    }

    @Test
    fun `explicit parallel and single engine scheduling pools retain their recipes`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.CSP),
            PortfolioScenario.parallel(4, Kind.COP),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.LOCAL_SEARCH),
            PortfolioScenario.sequential(Kind.COP).copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
        )
        for (scenario in scenarios) {
            val ordinary = ProblemFacts.assumed(scenario.kind)
            val scheduling = ProblemFacts(ordinary.profile.copy(scheduling = true)) { true }

            val selected = PortfolioComposition.compose(scenario, scheduling)

            assertEquals(PortfolioComposition.compose(scenario, ordinary).map { it.label }, selected.map { it.label })
        }
    }

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
