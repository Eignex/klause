package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {

    @Test
    fun `mixed optimization schedules a complete arm before local search`() {
        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP))

        assertIs<BacktrackWorkerConfig>(arms.first())
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
