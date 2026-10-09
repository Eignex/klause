package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortfolioPlanTest {

    @Test
    fun `portfolio reseeding accepts an off control and nonnegative thresholds`() {
        for ((params, expected) in listOf(emptyList<String>() to 3, listOf("reseed-stale-threshold=0") to 0,
            listOf("reseed-stale-threshold=2") to 2, listOf("reseed-stale-threshold=4") to 4)) {
            val scenario = buildPortfolioScenario(EngineParams(params), 1L, 1, Kind.COP, EngineMix.MIXED, 6)

            assertEquals(expected, scenario.reseedStaleThreshold)
        }
    }

    @Test
    fun `portfolio rejects a negative reseeding threshold`() {
        assertFailsWith<PipelineConfigException> {
            buildPortfolioScenario(EngineParams(listOf("reseed-stale-threshold=-1")), 1L, 1,
                Kind.COP, EngineMix.MIXED, 6)
        }
    }


    private fun plan(annotated: BacktrackParams?, params: List<String> = emptyList()) =
        FinitePipeline.planFixedBacktrack(
            FixedBacktrackPlanRequest(
                annotatedParams = annotated,
                engineParams = params,
                randomSeed = 1L,
                cancellation = Cancellation.Never,
                nodeBudget = null,
                solveBudgetMillis = null,
                lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                onEvent = null,
            ),
        )

    @Test
    fun `a model that states its own search is never branched by the lp`() {
        assertFalse(plan(BacktrackParams()).params.lpPlan.branching)
    }

    @Test
    fun `an unannotated model branches on the lp`() {
        assertTrue(plan(null).params.lpPlan.branching)
    }

    @Test
    fun `an explicit lp-branching param does not override a stated search`() {
        assertFalse(plan(BacktrackParams(), listOf("lp-branching=true")).params.lpPlan.branching)
    }
}
