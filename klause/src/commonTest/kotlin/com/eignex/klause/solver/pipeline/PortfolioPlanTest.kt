package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortfolioPlanTest {

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
