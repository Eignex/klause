package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpConfigTest {
    @Test
    fun `source preparation policy should parse independently of numerical techniques`() {
        val baseline = LpConfig.parse("aggressive,-cuts")
        for (mode in LpSourcePreparation.entries) {
            val config = LpConfig.parse("aggressive,-cuts,preparation=" + mode.name.lowercase())

            assertEquals(mode, config.sourcePreparation)
            assertEquals(baseline.emphasis, config.emphasis)
            assertEquals(baseline.overrides, config.overrides)
        }
    }

    @Test
    fun `preparation policy should not enable a numerical technique`() {
        val config = LpConfig.parse("off,preparation=disabled")

        assertTrue(LpTechnique.entries.none { config.resolved(it) })
    }

    @Test
    fun `preparation policy should reject unknown and repeated choices`() {
        for (spec in listOf("default,preparation=unknown", "preparation=",
            "preparation=object,preparation=index")) {
            assertFailsWith<IllegalStateException> { LpConfig.parse(spec) }
        }
    }

    @Test
    fun `an explicit preparation ceiling should override the arm policy`() {
        val arm = LpConfig(sourcePreparation = LpSourcePreparation.INDEX)
        val ceiling = LpConfig(sourcePreparation = LpSourcePreparation.DISABLED)

        assertEquals(LpSourcePreparation.DISABLED, arm.cappedUnder(ceiling).sourcePreparation)
    }

    @Test
    fun `an unspecified preparation ceiling should preserve the arm policy`() {
        val arm = LpConfig(sourcePreparation = LpSourcePreparation.OBJECT)

        assertEquals(LpSourcePreparation.OBJECT, arm.cappedUnder(LpConfig.AGGRESSIVE).sourcePreparation)
    }

    @Test
    fun `resolved plans should retain the requested source preparation policy`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val plan = LpAutoConfig.resolve(problem, LpConfig.parse("off,preparation=disabled"),
            LpPlan(sourcePreparation = LpSourcePreparation.INDEX))

        assertEquals(LpSourcePreparation.DISABLED, plan.sourcePreparation)
    }

    @Test
    fun `sparse trials require an explicit per invocation option`() {
        for (emphasis in LpEmphasis.entries) {
            assertFalse(LpConfig(emphasis).resolved(LpTechnique.SPARSE_TRIAL))
        }
        assertFalse(LpConfig.parse("all").resolved(LpTechnique.SPARSE_TRIAL))
        assertTrue(LpConfig.parse("default,+sparse-trial").resolved(LpTechnique.SPARSE_TRIAL))
    }

    @Test
    fun `parse of a technique list should force only those on`() {
        val cfg = LpConfig.parse("cuts,cumulative-flow")
        assertTrue(cfg.resolved(LpTechnique.CUTS))
        assertTrue(cfg.resolved(LpTechnique.CUMULATIVE_FLOW))
        assertFalse(cfg.resolved(LpTechnique.BOUNDING))
        assertFalse(cfg.resolved(LpTechnique.ENERGETIC))
    }

    @Test
    fun `parse should reject an unknown token`() {
        assertFailsWith<IllegalStateException> { LpConfig.parse("nonsense") }
    }

    @Test
    fun `an override should win over the emphasis tier`() {
        val forcedOn = LpConfig(LpEmphasis.CONSERVATIVE, mapOf(LpTechnique.CUTS to true))
        assertTrue(forcedOn.resolved(LpTechnique.CUTS))
        val forcedOff = LpConfig(LpEmphasis.AGGRESSIVE, mapOf(LpTechnique.BOUNDING to false))
        assertFalse(forcedOff.resolved(LpTechnique.BOUNDING))
    }

    @Test
    fun `parse of emphasis plus deltas should toggle individual techniques`() {
        val minusCuts = LpConfig.parse("aggressive,-cuts")
        assertEquals(LpEmphasis.AGGRESSIVE, minusCuts.emphasis)
        assertFalse(minusCuts.resolved(LpTechnique.CUTS))
        assertTrue(minusCuts.resolved(LpTechnique.BOUNDING))
        val justFlow = LpConfig.parse("off,+cumulative-flow")
        assertEquals(LpEmphasis.OFF, justFlow.emphasis)
        assertTrue(justFlow.resolved(LpTechnique.CUMULATIVE_FLOW))
        assertFalse(justFlow.resolved(LpTechnique.BOUNDING))
        assertFailsWith<IllegalStateException> { LpConfig.parse("aggressive,cuts") }
        assertFailsWith<IllegalStateException> { LpConfig.parse("default,+nonsense") }
    }

    @Test
    fun `cappedUnder should lower the emphasis and apply the ceiling overrides`() {
        val arm = LpConfig(LpEmphasis.AGGRESSIVE)
        assertEquals(LpEmphasis.DEFAULT, arm.cappedUnder(LpConfig(LpEmphasis.DEFAULT)).emphasis)
        assertEquals(LpEmphasis.AGGRESSIVE, arm.cappedUnder(LpConfig(LpEmphasis.AGGRESSIVE)).emphasis)
        val capped = arm.cappedUnder(LpConfig.parse("aggressive,-cuts"))
        assertFalse(capped.resolved(LpTechnique.CUTS))
        assertTrue(capped.resolved(LpTechnique.BOUNDING))
    }
}
