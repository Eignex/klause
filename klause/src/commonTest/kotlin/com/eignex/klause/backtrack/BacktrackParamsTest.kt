package com.eignex.klause.backtrack

import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpTechnique
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BacktrackParamsTest {
    @Test
    fun `LP free workers should admit cumulative unary subsets`() {
        for (config in listOf(null, LpConfig.OFF)) {
            for (plan in listOf(LpPlan(), LpPlan(branching = false, rootCutHarvest = false))) {
                val params = BacktrackParams(lpConfig = config, lpPlan = plan)

                assertTrue(params.useCumulativeUnarySubsets)
            }
        }
    }

    @Test
    fun `LP family settings should decline cumulative unary subsets`() {
        val cases = listOf(
            BacktrackParams(lpConfig = LpConfig.AUTO),
            BacktrackParams(lpPlan = LpPlan(bounding = true)),
            BacktrackParams(lpConfig = LpConfig(LpEmphasis.OFF, mapOf(LpTechnique.ENERGETIC to true))),
        )
        for (params in cases) {
            assertFalse(params.useCumulativeUnarySubsets)
        }
    }

    @Test
    fun `non-positive luby units should be rejected during construction`() {
        for (base in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { BacktrackParams(lubyRestartBase = base) }
        }
    }

    @Test
    fun `copies should reject invalid luby units even under adaptive restarts`() {
        val params = BacktrackParams(emaRestart = true)

        for (base in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { params.copy(lubyRestartBase = base) }
        }
    }

    @Test
    fun `negative inprocessing warmup should be rejected during construction`() {
        for (work in listOf(-1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { BacktrackParams(inprocessingWarmupWork = work) }
        }
    }
}
