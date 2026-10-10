package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpConfigTest {

    @Test
    fun `parse a technique list forces only those on`() {
        val cfg = LpConfig.parse("cuts,cumulative-flow")
        assertTrue(cfg.resolved(LpTechnique.CUTS))
        assertTrue(cfg.resolved(LpTechnique.CUMULATIVE_FLOW))
        assertFalse(cfg.resolved(LpTechnique.BOUNDING))
        assertFalse(cfg.resolved(LpTechnique.ENERGETIC))
    }

    @Test
    fun `parse rejects an unknown token`() {
        assertFailsWith<IllegalStateException> { LpConfig.parse("nonsense") }
    }

    @Test
    fun `an override wins over the emphasis tier`() {
        // Force CUTS on under CONSERVATIVE, and BOUNDING off under AGGRESSIVE.
        val forcedOn = LpConfig(LpEmphasis.CONSERVATIVE, mapOf(LpTechnique.CUTS to true))
        assertTrue(forcedOn.resolved(LpTechnique.CUTS))
        val forcedOff = LpConfig(LpEmphasis.AGGRESSIVE, mapOf(LpTechnique.BOUNDING to false))
        assertFalse(forcedOff.resolved(LpTechnique.BOUNDING))
    }

    @Test
    fun `parse emphasis plus deltas toggles individual techniques`() {
        // aggressive minus cuts: full LP but no cut rounds.
        val minusCuts = LpConfig.parse("aggressive,-cuts")
        assertEquals(LpEmphasis.AGGRESSIVE, minusCuts.emphasis)
        assertFalse(minusCuts.resolved(LpTechnique.CUTS))
        assertTrue(minusCuts.resolved(LpTechnique.BOUNDING))
        // off plus flow: no LP except the preemptive flow prune.
        val justFlow = LpConfig.parse("off,+cumulative-flow")
        assertEquals(LpEmphasis.OFF, justFlow.emphasis)
        assertTrue(justFlow.resolved(LpTechnique.CUMULATIVE_FLOW))
        assertFalse(justFlow.resolved(LpTechnique.BOUNDING))
        // A non-delta token after an emphasis is rejected.
        assertFailsWith<IllegalStateException> { LpConfig.parse("aggressive,cuts") }
        assertFailsWith<IllegalStateException> { LpConfig.parse("default,+nonsense") }
    }

    @Test
    fun `cappedUnder lowers the emphasis and applies the ceiling overrides`() {
        val arm = LpConfig(LpEmphasis.AGGRESSIVE)
        // Capping under DEFAULT lowers the emphasis; a higher ceiling is a no-op.
        assertEquals(LpEmphasis.DEFAULT, arm.cappedUnder(LpConfig(LpEmphasis.DEFAULT)).emphasis)
        assertEquals(LpEmphasis.AGGRESSIVE, arm.cappedUnder(LpConfig(LpEmphasis.AGGRESSIVE)).emphasis)
        // A `-cuts` ceiling forces cuts off even on the AGGRESSIVE arm.
        val capped = arm.cappedUnder(LpConfig.parse("aggressive,-cuts"))
        assertFalse(capped.resolved(LpTechnique.CUTS))
        assertTrue(capped.resolved(LpTechnique.BOUNDING))
    }
}
