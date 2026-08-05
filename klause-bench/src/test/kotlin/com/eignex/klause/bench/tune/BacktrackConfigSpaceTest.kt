package com.eignex.klause.bench.tune

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktrackConfigSpaceTest {

    @Test
    fun `sampled points decode to params and gate the lp child knobs`() {
        val rng = Random(1)
        val presets = mutableSetOf<String>()
        repeat(1000) {
            val a = BacktrackConfigSpace.sample(rng)
            presets += a["preset"] as String
            // The LP-plan dials are children of lp.emphasis != off.
            val lpOn = a["lp.emphasis"] != "off"
            for (child in listOf("lp.lbtree", "lp.objective-cone", "lp.auto-off-reprobe", "lp.knapsack-lagrangian")) {
                assertEquals(lpOn, a.containsKey(child), "$child gated by lp.emphasis: $a")
            }
            // Decodes without throwing; LP config presence matches the emphasis choice.
            val p = BacktrackConfigSpace.toParams(a)
            assertEquals(!lpOn, p.lpConfig == null, "lpConfig set iff emphasis != off: $a")
        }
        assertEquals(setOf("conflictDriven", "satOptimized", "free"), presets, "all presets reachable")
    }

    @Test
    fun `decoding maps knobs onto the params`() {
        val a = mapOf<String, Any>(
            "preset" to "free",
            "var-selector" to "chb",
            "val-selector" to "min",
            "luby" to "256",
            "phase-saving" to "true",
            "target-phasing" to "true",
            "adaptive-restart" to "false",
            "vivification" to "true",
            "tiered-db" to "true",
            "max-learned" to "20000",
            "rephase-interval" to 500,
            "lbd-glue" to 3,
            "mid-lbd" to 8,
            "vivify-batch" to 128,
            "inprocessing-cadence" to 4,
            "lp.emphasis" to "off",
        )
        val p = BacktrackConfigSpace.toParams(a)
        assertEquals(256L, p.lubyRestartBase)
        assertTrue(p.phaseSaving)
        assertTrue(p.targetPhasing)
        assertTrue(p.vivification)
        assertTrue(p.tieredLearnedDb)
        assertEquals(20000, p.maxLearnedClauses)
        assertEquals(500L, p.rephaseInterval)
        assertEquals(3, p.lbdGlueThreshold)
        assertEquals(8, p.midLbdThreshold)
        assertEquals(128, p.vivifyBatch)
        assertEquals(4, p.inprocessingCadence)
        assertEquals(null, p.lpConfig, "emphasis=off -> no LP")
    }

    @Test
    fun `every var-selector value decodes to a real variable selector`() {
        val values = (BacktrackConfigSpace.params.first { it.name == "var-selector" } as CategoricalParam).values
        for (v in values) {
            val p = BacktrackConfigSpace.toParams(baseAssignment("var-selector" to v))
            assertTrue(p.variableSelector.toString().isNotEmpty(), "var-selector=$v builds a selector")
        }
    }

    @Test
    fun `every val-selector value decodes to a real value selector`() {
        val values = (BacktrackConfigSpace.params.first { it.name == "val-selector" } as CategoricalParam).values
        for (v in values) {
            val p = BacktrackConfigSpace.toParams(baseAssignment("val-selector" to v))
            assertTrue(p.valueSelector.toString().isNotEmpty(), "val-selector=$v builds a selector")
        }
    }

    @Test
    fun `the probing selectors decode to buildable params`() {
        val vsids = BacktrackConfigSpace.toParams(baseAssignment("var-selector" to "vsids")).variableSelector
        for (v in listOf("domwdeg", "activity")) {
            val sel = BacktrackConfigSpace.toParams(baseAssignment("var-selector" to v)).variableSelector
            assertTrue(sel != vsids, "var-selector=$v builds a distinct selector")
        }
        val min = BacktrackConfigSpace.toParams(baseAssignment("val-selector" to "min")).valueSelector
        val impact = BacktrackConfigSpace.toParams(baseAssignment("val-selector" to "impact")).valueSelector
        assertTrue(impact != min, "val-selector=impact builds a distinct selector")
    }

    @Test
    fun `lp-plan dials decode onto the resolved plan when emphasis is on`() {
        val a = baseAssignment(
            "lp.emphasis" to "aggressive",
            "lp.lbtree" to "true",
            "lp.objective-cone" to "true",
            "lp.auto-off-reprobe" to "false",
            "lp.knapsack-lagrangian" to "true",
        )
        val p = BacktrackConfigSpace.toParams(a)
        assertTrue(p.lpPlan.lbTreeSearch)
        assertTrue(p.lpPlan.objectiveCone)
        assertEquals(false, p.lpPlan.autoOffReprobe)
        assertTrue(p.lpPlan.knapsackLagrangian)
    }

    @Test
    fun `random samples over the whole space all decode without throwing`() {
        val rng = Random(42)
        repeat(2000) {
            val a = BacktrackConfigSpace.sample(rng)
            BacktrackConfigSpace.toParams(a) // throws on any undecodable value
        }
    }

    /** A fully-populated assignment with every param at a valid value, overridden by [overrides]. LP
     *  child dials are included so an emphasis-on assignment stays well-formed. */
    private fun baseAssignment(vararg overrides: Pair<String, Any>): Map<String, Any> = buildMap {
        put("preset", "free")
        put("var-selector", "vsids")
        put("val-selector", "min")
        put("luby", "off")
        put("phase-saving", "false")
        put("target-phasing", "false")
        put("adaptive-restart", "false")
        put("vivification", "false")
        put("tiered-db", "false")
        put("max-learned", "off")
        put("rephase-interval", 1000)
        put("lbd-glue", 2)
        put("mid-lbd", 6)
        put("vivify-batch", 256)
        put("inprocessing-cadence", 1)
        put("lp.emphasis", "off")
        put("lp.lbtree", "false")
        put("lp.objective-cone", "false")
        put("lp.auto-off-reprobe", "true")
        put("lp.knapsack-lagrangian", "false")
        putAll(overrides)
    }
}
