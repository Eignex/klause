package com.eignex.klause.solver.backtrack.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #429: the LP emphasis config — the presolve-mirrored selector surface. */
class LpConfigTest {

    @Test
    fun `parse maps the emphasis keywords`() {
        assertEquals(LpEmphasis.DEFAULT, LpConfig.parse(null).emphasis)
        assertEquals(LpEmphasis.DEFAULT, LpConfig.parse("").emphasis)
        assertEquals(LpEmphasis.DEFAULT, LpConfig.parse("default").emphasis)
        assertEquals(LpEmphasis.DEFAULT, LpConfig.parse("auto").emphasis)
        assertEquals(LpEmphasis.OFF, LpConfig.parse("off").emphasis)
        assertEquals(LpEmphasis.OFF, LpConfig.parse("none").emphasis)
        assertEquals(LpEmphasis.CONSERVATIVE, LpConfig.parse("conservative").emphasis)
        assertEquals(LpEmphasis.CONSERVATIVE, LpConfig.parse("fast").emphasis)
        assertEquals(LpEmphasis.AGGRESSIVE, LpConfig.parse("aggressive").emphasis)
    }

    @Test
    fun `parse all forces every technique on`() {
        val all = LpConfig.parse("all")
        assertTrue(LpTechnique.entries.all { all.resolved(it) })
    }

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
    fun `emphasis ids and fromId are a single source of truth with aliases`() {
        assertEquals("off | conservative | default | aggressive", LpEmphasis.ids())
        // Every canonical id round-trips; the aliases map to the same value.
        for (e in LpEmphasis.entries) assertEquals(e, LpEmphasis.fromId(e.id))
        assertEquals(LpEmphasis.OFF, LpEmphasis.fromId("none"))
        assertEquals(LpEmphasis.CONSERVATIVE, LpEmphasis.fromId("fast"))
        assertEquals(LpEmphasis.DEFAULT, LpEmphasis.fromId("auto"))
        assertEquals(LpEmphasis.DEFAULT, LpEmphasis.fromId(null)) // blank/absent → DEFAULT
        assertEquals(null, LpEmphasis.fromId("nonsense"))
        assertEquals(LpTechnique.entries.joinToString(" | ") { it.id }, LpTechnique.ids())
    }

    @Test
    fun `resolved follows the emphasis cost tiers`() {
        // OFF: nothing. CONSERVATIVE: FAST only. DEFAULT: FAST+MEDIUM. AGGRESSIVE: all.
        assertTrue(LpTechnique.entries.none { LpConfig(LpEmphasis.OFF).resolved(it) })

        val conservative = LpConfig(LpEmphasis.CONSERVATIVE)
        assertTrue(conservative.resolved(LpTechnique.ENERGETIC)) // FAST
        assertFalse(conservative.resolved(LpTechnique.BOUNDING)) // MEDIUM
        assertFalse(conservative.resolved(LpTechnique.CUTS)) // EXHAUSTIVE

        val default = LpConfig(LpEmphasis.DEFAULT)
        assertTrue(default.resolved(LpTechnique.ENERGETIC))
        assertTrue(default.resolved(LpTechnique.BOUNDING))
        assertFalse(default.resolved(LpTechnique.CUTS))

        assertTrue(LpTechnique.entries.all { LpConfig(LpEmphasis.AGGRESSIVE).resolved(it) })
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
