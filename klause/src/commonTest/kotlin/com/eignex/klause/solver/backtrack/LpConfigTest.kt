package com.eignex.klause.solver.backtrack

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
    fun `cappedAt lowers the emphasis and never raises it`() {
        assertEquals(LpEmphasis.DEFAULT, LpConfig(LpEmphasis.AGGRESSIVE).cappedAt(LpEmphasis.DEFAULT).emphasis)
        assertEquals(LpEmphasis.OFF, LpConfig(LpEmphasis.DEFAULT).cappedAt(LpEmphasis.OFF).emphasis)
        // Capping at a higher ceiling is a no-op (the config stays where it is).
        assertEquals(
            LpEmphasis.CONSERVATIVE,
            LpConfig(LpEmphasis.CONSERVATIVE).cappedAt(LpEmphasis.AGGRESSIVE).emphasis,
        )
    }
}
