package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertIs

class PortfolioCompositionTest {

    @Test
    fun `mixed optimization schedules a complete arm before local search`() {
        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP))

        assertIs<BacktrackWorkerConfig>(arms.first())
    }
}
