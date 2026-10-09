package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {

    @Test
    fun `expanding a sequential optimization pool keeps the small pool positions`() {
        val small = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts.assumed(Kind.COP)

        val baseline = PortfolioComposition.plan(small, facts)
        val expanded = PortfolioComposition.plan(small.copy(arms = 12), facts)

        assertEquals(baseline.arms.map { it.label }, expanded.arms.take(baseline.arms.size).map { it.label })
        assertEquals(baseline.arms.size, expanded.firstSolutionCount)
        assertEquals(13, expanded.arms.size)
    }

    @Test
    fun `an expanded annotated pool keeps default LP in its first solution pool`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, arms = 12).copy(annotationArm = BacktrackParams())

        val plan = PortfolioComposition.plan(scenario, ProblemFacts.assumed(Kind.COP))
        val first = plan.arms.take(plan.firstSolutionCount)

        assertEquals(listOf("satOptimized", "annotation"), first.take(2).map { it.label })
        assertEquals("lp-default", first.last().label)
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
    fun `a model with continuous columns gets the default LP arm after the first two backtrack arms`() {
        for (kind in Kind.entries) {
            val scenario = PortfolioScenario.sequential(kind, engine = EngineMix.BACKTRACK, arms = 6)

            val facts = ProblemFacts.assumed(kind, ProblemClass.MixedInteger)
            val backtrack = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

            assertEquals(
                listOf("satOptimized", "conflictDriven", "lp-default"),
                backtrack.take(3).map { it.label },
                "$kind",
            )
        }
    }

    @Test
    fun `a pseudo-Boolean model leads its local search with the flip and jump walks`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.LOCAL_SEARCH, arms = 3)

        val labels = PortfolioComposition.compose(scenario, ProblemFacts.assumed(Kind.COP, ProblemClass.PseudoBoolean))
            .map { it.label }

        assertEquals(listOf("probsat-bandit/fixed", "fjump/fixed"), labels.take(2))
    }

    @Test
    fun `a finite optimization pool covers the default relaxation with two backtrack slots`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.MIXED, arms = 6)

        val backtrack = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals(listOf("satOptimized", "lp-default"), backtrack.map { it.label })
    }

    @Test
    fun `a finite optimization pool without a relaxation keeps its LP free cores`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, relaxation = { false })

        val backtrack = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals(listOf("satOptimized", "conflictDriven"), backtrack.map { it.label })
    }

    @Test
    fun `a small finite optimization pool retains the model search annotation`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = BacktrackParams())

        val backtrack = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals(listOf("satOptimized", "annotation"), backtrack.map { it.label })
    }

    @Test
    fun `a binary integer pool keeps its LP free cores for first solutions`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, binaryIntegers = true) { true }

        val backtrack = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals(listOf("satOptimized", "conflictDriven"), backtrack.map { it.label })
    }

    @Test
    fun `a model with nothing to relax builds no LP arm and keeps the pool full`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 6)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, relaxation = { false })

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertEquals(6, labels.size)
        assertTrue(labels.none { it.startsWith("lp-") }, "labels: $labels")
    }

    @Test
    fun `an edited curated pool still builds no LP arm on a model with nothing to relax`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 6)
            .copy(btEdit = { it.copy(lubyRestartBase = 7L) })
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, relaxation = { false })

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertTrue(labels.none { it.startsWith("lp-") }, "labels: $labels")
    }

    @Test
    fun `an edit reaches every arm the curated pool builds`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 4)
            .copy(btEdit = { it.copy(lubyRestartBase = 7L) })

        val arms = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals(listOf(7L, 7L, 7L, 7L), arms.map { it.recipe.build(1L, null).lubyRestartBase })
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

    @Test
    fun `pricing override retains the annotation arm in the default pool`() {
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 3).copy(
                annotationArm = BacktrackParams(),
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
        ).filterIsInstance<BacktrackWorkerConfig>()

        assertEquals("satOptimized", arms.first().label)
        assertEquals("annotation", arms.last().label)
        assertTrue(arms.all { it.zeroObjectivePricing == LpZeroObjectivePricing.LARGEST_PIVOT })
    }

    @Test
    fun `capping a run does not change which arms it composes`() {
        for (engine in EngineMix.entries) {
            for (kind in Kind.entries) {
                val scenario = PortfolioScenario.sequential(kind, engine = engine, arms = 6)
                assertEquals(
                    PortfolioComposition.compose(scenario).map { it.label },
                    PortfolioComposition.compose(scenario.copy(nodeBudget = NodeBudget(limit = 100))).map { it.label },
                    "$engine/$kind composes a different pool once a node cap is set",
                )
            }
        }
    }
}
